package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.ColorSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.basic.dto.MaterialDetailVO;
import com.lingmao.scm.module.basic.dto.MaterialDupVO;
import com.lingmao.scm.module.basic.dto.MaterialListVO;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickVO;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.SkuSpecReq;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.entity.BizType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 物料在真库上（RFC 003 §16）：SKU 被价格档案引用时删物料被外键拦住（1005），SKU、图都还在；
 * 导入以后流水推到最大号，再新建不撞；建档查重、列表、选 SKU 的 SQL。
 * 每个测试自己建分类（随机的 2 位字母编码）、颜色，不依赖库里有什么
 */
@IntegrationTest
class MaterialServiceImplIT {

    @Autowired
    private MaterialService materialService;
    @Autowired
    private MaterialCategoryService materialCategoryService;
    @Autowired
    private ColorService colorService;
    @Autowired
    private GlobalExceptionHandler handler;
    @Autowired
    private FileUrlSigner signer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 规则：SKU 被价格档案引用了，删物料整个回滚：返回 1005"已被价格档案引用"，SKU、物料的图、SKU 的图都还在 */
    @Test
    void delete_skuReferencedByPrice_1005AndNothingDeleted() {
        Leaf leaf = leafCategory();
        long colorId = color();
        MaterialCreateReq req = createReq(leaf.id(), "外键测试面料");
        req.setColorIds(List.of(colorId));
        long id = materialService.create(req);
        long skuId = jdbcTemplate.queryForObject("SELECT id FROM bas_material_sku WHERE material_id = ?", Long.class, id);
        materialService.replaceImages(id, List.of(image("m")));
        materialService.replaceSkuImages(id, skuId, List.of(image("k")));
        long supplierId = supplier();
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, material_sku_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, ?, 12.5, 'METER', 13, CURRENT_DATE)""", supplierId, id, skuId);

        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> materialService.delete(id));

        R<Void> r = handler.handleDataIntegrity(e, new MockHttpServletRequest("DELETE", "/api/materials/" + id));
        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(r.getMsg()).isEqualTo("已被价格档案引用，不能删除，只能停用");
        assertThat(count("SELECT count(*) FROM bas_material_sku WHERE material_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'MATERIAL' AND doc_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'MATERIAL_SKU' AND doc_id = ?", skuId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'MATERIAL' AND doc_id = ?", id)).isPositive();

        jdbcTemplate.update("DELETE FROM prc_price WHERE material_id = ?", id);
        materialService.delete(id);
        assertThat(count("SELECT count(*) FROM bas_material WHERE id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'MATERIAL_SKU' AND doc_id = ?", skuId)).isZero();
    }

    /**
     * 规则（RFC 003 §7.1）：导入领猫的老编码 XX0042，流水推到 42；之后页面上新建的从 XX0043 开始，不撞。
     * 颜色 × 规格：两个规格各一个 SKU，码 = 物料编码-颜色-规格码
     */
    @Test
    void import_raisesSerial_thenCreateDoesNotCollide() throws IOException {
        Leaf leaf = leafCategory();
        String path = leaf.parentName() + ">" + leaf.name();
        byte[] bytes = workbook(new Object[][]{{"物料类型", "物料分类", "物料编码", "物料名称", "单位"},
                {"面料", path, leaf.code() + "0042", "导入的老面料", "米"},
                {"面料", path, leaf.code() + "0007", "导入的更老的面料", "米"}});

        assertThat(materialService.importExcel(new MockMultipartFile("file", "物料.xlsx", Excel.CONTENT_TYPE, bytes)).total())
                .isEqualTo(2);
        long colorId = color();
        String colorCode = jdbcTemplate.queryForObject("SELECT code FROM bas_color WHERE id = ?", String.class, colorId);
        MaterialCreateReq req = createReq(leaf.id(), "新建的面料");
        req.setColorIds(List.of(colorId));
        req.setSpecs(List.of(spec("150cm"), spec("180 cm")));
        long id = materialService.create(req);

        MaterialDetailVO detail = materialService.detail(id);
        assertThat(detail.material().getCode()).isEqualTo(leaf.code() + "0043");
        assertThat(detail.skus()).extracting(s -> s.code()).containsExactlyInAnyOrder(
                leaf.code() + "0043-" + colorCode + "-150", leaf.code() + "0043-" + colorCode + "-180");
        assertThat(count("SELECT count(*) FROM bas_material_sku k JOIN bas_material m ON m.id = k.material_id "
                + "WHERE m.code = ? AND k.code = m.code", leaf.code() + "0042")).isEqualTo(1);   // 没有 SKU 行的建了默认 SKU
    }

    /** 规则（PRD §6.1.5 验收 2）：同分类同名（不分大小写）、同默认供应商同货号的列出来；排除自己；都不填不查 */
    @Test
    void duplicates_byNameOrSupplierItemNo() {
        Leaf leaf = leafCategory();
        long supplierId = supplier();
        MaterialCreateReq req = createReq(leaf.id(), "Cotton 府绸");
        req.setDefaultSupplierId(supplierId);
        req.setSupplierItemNo("HF-001");
        long id = materialService.create(req);
        Leaf other = leafCategory();

        List<MaterialDupVO> byName = materialService.duplicates(leaf.id(), " cotton 府绸 ", null, null, null);
        assertThat(byName).singleElement().satisfies(d -> {
            assertThat(d.getId()).isEqualTo(id);
            assertThat(d.getReason()).isEqualTo("同分类同名");
            assertThat(d.getDefaultSupplierName()).startsWith("IT供应商");
        });
        assertThat(materialService.duplicates(other.id(), "别的名字", supplierId, "hf-001", null))
                .singleElement().extracting(MaterialDupVO::getReason).isEqualTo("同供应商同货号");
        assertThat(materialService.duplicates(other.id(), "Cotton 府绸", null, null, null)).isEmpty();
        assertThat(materialService.duplicates(leaf.id(), "Cotton 府绸", supplierId, "HF-001", id)).isEmpty();
        assertThat(materialService.duplicates(null, null, null, null, null)).isEmpty();
    }

    /** 列表：一级分类连同下面的二级一起查、空格隔开查几个编码；选 SKU：停用的 SKU 不出来，单价没填取物料的参考价 */
    @Test
    void pageAndPick_sql() {
        Leaf leaf = leafCategory();
        long colorA = color();
        long colorB = color();
        MaterialCreateReq req = createReq(leaf.id(), "列表测试面料");
        req.setColorIds(List.of(colorA, colorB));
        req.setRefPrice(new BigDecimal("18.5"));
        long id = materialService.create(req);
        long other = materialService.create(createReq(leaf.id(), "列表测试面料二"));
        String code = jdbcTemplate.queryForObject("SELECT code FROM bas_material WHERE id = ?", String.class, id);
        String otherCode = jdbcTemplate.queryForObject("SELECT code FROM bas_material WHERE id = ?", String.class, other);

        MaterialQuery byParent = new MaterialQuery();
        byParent.setCategoryId(leaf.parentId());
        List<MaterialListVO> rows = materialService.page(byParent).getList();
        assertThat(rows).extracting(MaterialListVO::getId).containsExactly(id, other);
        assertThat(rows.getFirst().getSkuCount()).isEqualTo(2);
        assertThat(rows.getFirst().getParentCategoryName()).isEqualTo(leaf.parentName());
        MaterialQuery byCodes = new MaterialQuery();
        byCodes.setKeyword(code.toLowerCase() + " " + otherCode);
        assertThat(materialService.page(byCodes).getList()).extracting(MaterialListVO::getId).containsExactlyInAnyOrder(id, other);

        long skuB = jdbcTemplate.queryForObject("SELECT id FROM bas_material_sku WHERE material_id = ? AND color_id = ?",
                Long.class, id, colorB);
        materialService.disableSku(id, skuB);
        MaterialPickQuery pick = new MaterialPickQuery();
        pick.setKeyword(code);
        List<MaterialPickVO> picked = materialService.pick(pick).getList();
        assertThat(picked).singleElement().satisfies(p -> {
            assertThat(p.getColorId()).isEqualTo(colorA);
            assertThat(p.getPrice()).isEqualByComparingTo("18.5");
        });
    }

    // ---------- 造数据 ----------

    private record Leaf(long id, long parentId, String code, String name, String parentName) {
    }

    /** 一个新的一级分类 + 它下面一个二级分类，编码随机取一个没用过的 */
    private Leaf leafCategory() {
        String suffix = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L), 36);
        String parentName = "IT一级" + suffix;
        long parentId = materialCategoryService.create(categoryReq(null, null, parentName));
        String code;
        do {
            code = "" + (char) ('A' + ThreadLocalRandom.current().nextInt(26)) + (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        } while (count("SELECT count(*) FROM bas_material_category WHERE code = ?", code) > 0);
        String name = "IT二级" + suffix;
        long id = materialCategoryService.create(categoryReq(parentId, code, name));
        return new Leaf(id, parentId, code, name, parentName);
    }

    private long color() {
        String code;
        do {
            code = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36), 36).toUpperCase();
        } while (code.length() < 2 || count("SELECT count(*) FROM bas_color WHERE code = ?", code) > 0);
        ColorSaveReq req = new ColorSaveReq();
        req.setCode(code);
        req.setName("IT色" + code + ThreadLocalRandom.current().nextInt(1_000_000));
        return colorService.create(req);
    }

    private long supplier() {
        String code = "ITS" + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        return jdbcTemplate.queryForObject("INSERT INTO sup_supplier (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, code, "IT供应商全称" + code, "IT供应商" + code);
    }

    private static MaterialCategorySaveReq categoryReq(Long parentId, String code, String name) {
        MaterialCategorySaveReq req = new MaterialCategorySaveReq();
        req.setParentId(parentId);
        req.setCode(code);
        req.setName(name);
        return req;
    }

    private static MaterialCreateReq createReq(long categoryId, String name) {
        MaterialCreateReq req = new MaterialCreateReq();
        req.setCategoryId(categoryId);
        req.setName(name);
        req.setMaterialType("FABRIC");
        req.setStockUnit("METER");
        return req;
    }

    private static SkuSpecReq spec(String spec) {
        SkuSpecReq req = new SkuSpecReq();
        req.setSpec(spec);
        return req;
    }

    private AttachmentReq image(String name) {
        AttachmentReq req = new AttachmentReq();
        req.setUrl(signer.sign("/files/2026/11/15/" + name + ".jpg"));
        req.setName(name + ".jpg");
        req.setSize(1024L);
        return req;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** 一张"物料信息"表 */
    private static byte[] workbook(Object[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("物料信息");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    row.createCell(j).setCellValue(rows[i][j].toString());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
