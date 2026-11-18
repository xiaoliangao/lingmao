package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.dto.StyleListVO;
import com.lingmao.scm.module.style.dto.StylePickQuery;
import com.lingmao.scm.module.style.dto.StylePickVO;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.dto.StyleSkuSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuVO;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.cache.DictCache;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 款式在真库上（RFC 005 §10、§12）：同一设计师并发建款号不重复、设计师编号锁上；花名能搜；
 * 一键生成 ON CONFLICT 只补缺；颜色、条码的唯一索引；被价格档案引用时删除被外键拦住（1005）、什么都没删；
 * 导入建品牌、生成 SKU；列表、选款式的 SQL。每个测试自己造品类、尺码组、颜色、设计师、品牌，名字随机
 */
@IntegrationTest
class StyleServiceImplIT {

    private static final List<String> SIZES = List.of("S", "M", "L", "XL", "XXL");

    @org.junit.jupiter.api.BeforeEach
    void loginFixture() {
        var permissions = List.of("style:style:import", "style:style:add", "style:style:edit", "techpack:cost:query");
        TestIdentity.grant(jdbcTemplate, 900502L, permissions);
        TestIdentity.login(900502L, permissions);
    }

    @org.junit.jupiter.api.AfterEach
    void clearIdentity() { TestIdentity.clear(); }

    @Autowired
    private StyleService styleService;
    @Autowired
    private UserService userService;
    @Autowired
    private DictDataService dictDataService;
    @Autowired
    private GlobalExceptionHandler handler;
    @Autowired
    private FileUrlSigner signer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DictCache dictCache;

    /** 品牌是迁移要求为空的共享字典；只清理本例创建的行，不能让类执行顺序影响预置数据检查。 */
    private final List<Long> fixtureBrandIds = new ArrayList<>();

    @AfterEach
    void removeFixtureBrands() {
        try {
            for (Long id : fixtureBrandIds) {
                // Service.delete 是逻辑删除；夹具应物理清理，SchemaConventionsIT 要核对所有品牌行均未预置。
                jdbcTemplate.update("DELETE FROM sys_dict_data WHERE id = ? AND type_code = 'brand'", id);
            }
        } finally {
            if (!fixtureBrandIds.isEmpty()) dictCache.evictAfterCommit("brand");
        }
    }

    /**
     * 规则（RFC 005 §10）：同一设计师同一天两个人同时建款，sys_serial 这一行的锁让他们排队，款号不重复、连着号；
     * 设计师编号第一次用来编号后锁上，再用一个对不上的编号去锁 → 1002
     */
    @Test
    void create_concurrentSameDesigner_codesDistinct() throws Exception {
        Fixture f = fixture();
        CountDownLatch start = new CountDownLatch(1);
        Callable<StyleCreateResult> task = () -> {
            start.await();
            return styleService.create(req(f, "并发测试款"));
        };
        List<StyleCreateResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<StyleCreateResult> a = pool.submit(task);
            Future<StyleCreateResult> b = pool.submit(task);
            start.countDown();
            results.add(a.get());
            results.add(b.get());
        }

        String prefix = "FS" + LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyMMdd")) + f.designerNo();
        assertThat(results).extracting(StyleCreateResult::code).doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).startsWith(prefix).hasSize(prefix.length() + 2));
        assertThat(jdbcTemplate.queryForObject("SELECT designer_no_locked FROM sys_user WHERE id = ?", Boolean.class, f.designerId()))
                .isTrue();
        BizException e = catchThrowableOfType(BizException.class, () -> userService.lockDesignerNo(f.designerId(), "00"));
        assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);
    }

    /** 规则（PRD §6.2 验收 7 前半）：花名能搜；关键字有空格时按几个款号查；品类按上级筛连同下级 */
    @Test
    void page_aliasCodesAndCategory() {
        Fixture f = fixture();
        StyleCreateReq first = req(f, "列表测试款");
        first.setAliasName("小风衣");
        StyleCreateResult a = styleService.create(first);
        StyleCreateResult b = styleService.create(req(f, "列表测试款二"));

        StyleQuery byAlias = new StyleQuery();
        byAlias.setKeyword("小风衣");
        assertThat(styleService.page(byAlias).getList()).extracting(StyleListVO::getCode).contains(a.code());

        StyleQuery byCodes = new StyleQuery();
        byCodes.setKeyword(a.code().toLowerCase() + " " + b.code());
        assertThat(styleService.page(byCodes).getList()).extracting(StyleListVO::getCode)
                .containsExactlyInAnyOrder(a.code(), b.code());

        StyleQuery byParent = new StyleQuery();
        byParent.setCategoryId(f.parentCategoryId());
        List<StyleListVO> rows = styleService.page(byParent).getList();
        assertThat(rows).extracting(StyleListVO::getCode).containsExactlyInAnyOrder(a.code(), b.code());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getCategoryName()).isEqualTo(f.leafName());
            assertThat(r.getSizeCount()).isEqualTo(5);
            assertThat(r.getColorCount()).isEqualTo(1);
            assertThat(r.getSkuCount()).isEqualTo(5);
            assertThat(r.getDesignerName()).isEqualTo(f.designerName());
        });
    }

    /**
     * 规则（RFC 005 §5）：建款带一色生成 5 个；再点生成 0 个（ON CONFLICT 只补缺）；加第二色再生成补 5 个；
     * 同一个颜色再加一次 → 1000；生成过 SKU 的颜色不能去掉。启用后选款式能选到它，草稿时选不到；选款式按品牌名称能搜到
     */
    @Test
    void colorsAndGenerate_idempotent() {
        Fixture f = fixture();
        StyleCreateResult r = styleService.create(req(f, "生成测试款"));
        assertThat(r.skuCount()).isEqualTo(5);
        assertThat(styleService.generateSkus(r.id())).isZero();

        long second = color();
        styleService.addColors(r.id(), List.of(second));
        assertThat(styleService.generateSkus(r.id())).isEqualTo(5);
        assertThat(styleService.skus(r.id())).hasSize(10)
                .first().extracting(StyleSkuVO::code).isEqualTo(r.code() + "-" + f.colorCode() + "-S");

        assertThat(catchThrowableOfType(BizException.class, () -> styleService.addColors(r.id(), List.of(second))))
                .hasMessageStartingWith("已经有这个颜色了");
        assertThat(catchThrowableOfType(BizException.class, () -> styleService.removeColor(r.id(), second)))
                .hasMessageStartingWith("这个颜色已经生成了 SKU，只能停用");
        // 两个人同时加同一个颜色：唯一索引兜底
        assertThat(catchThrowableOfType(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 9)", r.id(), second))).isNotNull();

        StylePickQuery pick = new StylePickQuery();
        pick.setKeyword(r.code());
        assertThat(styleService.pick(pick).getList()).isEmpty();
        styleService.enable(r.id());
        pick.setCategoryId(f.parentCategoryId());
        List<StylePickVO> picked = styleService.pick(pick).getList();
        assertThat(picked).singleElement().satisfies(p -> {
            assertThat(p.getId()).isEqualTo(r.id());
            assertThat(p.getColors()).hasSize(2);
        });
        // 品牌按名称搜（不分大小写），字典的 value 不算
        StylePickQuery byBrand = new StylePickQuery();
        byBrand.setKeyword(f.brandLabel().toUpperCase());
        assertThat(styleService.pick(byBrand).getList()).extracting(StylePickVO::getId).containsExactly(r.id());
        byBrand.setKeyword(f.brand());
        assertThat(styleService.pick(byBrand).getList()).isEmpty();
        // 几个款号一起查
        StylePickQuery byCodes = new StylePickQuery();
        byCodes.setKeyword(r.code().toLowerCase() + "，NO-SUCH-STYLE");
        assertThat(styleService.pick(byCodes).getList()).extracting(StylePickVO::getId).containsExactly(r.id());
    }

    /** 规则：条码有值时全表唯一（部分唯一索引），Service 先查给出是哪个 SKU；空的不算重复 */
    @Test
    void barcode_uniqueWhenSet() {
        Fixture f = fixture();
        StyleCreateResult r = styleService.create(req(f, "条码测试款"));
        List<StyleSkuVO> skus = styleService.skus(r.id());
        String barcode = "69" + ThreadLocalRandom.current().nextLong(1_000_000_000_000L);
        styleService.updateSku(r.id(), skus.get(0).id(), skuReq(barcode));
        styleService.updateSku(r.id(), skus.get(1).id(), skuReq(null));
        styleService.updateSku(r.id(), skus.get(2).id(), skuReq(null));

        assertThat(catchThrowableOfType(BizException.class, () -> styleService.updateSku(r.id(), skus.get(1).id(), skuReq(barcode))))
                .hasMessage("条码 " + barcode + " 已经给了 SKU " + skus.get(0).code());
        assertThat(catchThrowableOfType(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "UPDATE sty_style_sku SET barcode = ? WHERE id = ?", barcode, skus.get(1).id()))).isNotNull();
    }

    /**
     * 规则（RFC 005 §4、§10）：款式被价格档案引用了，删除整个回滚：1005"已被价格档案引用"，SKU、颜色、图、操作记录都还在；
     * 去掉引用以后能删干净
     */
    @Test
    void delete_referencedByPrice_1005AndNothingDeleted() {
        Fixture f = fixture();
        StyleCreateResult r = styleService.create(req(f, "外键测试款"));
        styleService.replaceImages(r.id(), List.of(image("s")));
        long supplierId = supplier();
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, style_id, process_mode, price, unit, tax_pct, effective_date)
                VALUES ('PROCESS', ?, ?, 'CMT', 35, 'PIECE', 0, CURRENT_DATE)""", supplierId, r.id());

        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> styleService.delete(r.id()));

        R<Void> result = handler.handleDataIntegrity(e, new MockHttpServletRequest("DELETE", "/api/styles/" + r.id()));
        assertThat(result.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(result.getMsg()).isEqualTo("已被价格档案引用，不能删除，只能停用");
        assertThat(count("SELECT count(*) FROM sty_style_sku WHERE style_id = ?", r.id())).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM sty_style_color WHERE style_id = ?", r.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'STYLE' AND doc_id = ?", r.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'STYLE' AND doc_id = ?", r.id())).isPositive();

        jdbcTemplate.update("DELETE FROM prc_price WHERE style_id = ?", r.id());
        styleService.delete(r.id());
        assertThat(count("SELECT count(*) FROM sty_style WHERE id = ?", r.id())).isZero();
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'STYLE' AND doc_id = ?", r.id())).isZero();
    }

    /**
     * 规则（RFC 005 §8）：导入在真库上：品牌字典里没有的新建、默认加工厂按简称对上、颜色 × 尺码生成 SKU、状态启用；
     * 导出两张表，款式表能原样认回来（款号已存在的报错，导入只新增）。导入的款没有设计师，顺带测取名字不出空指针
     */
    @Test
    void importAndExport_realDatabase() throws IOException {
        Fixture f = fixture();
        long supplierId = supplier();
        String shortName = jdbcTemplate.queryForObject("SELECT short_name FROM sup_supplier WHERE id = ?", String.class, supplierId);
        String code = "IT" + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        String brand = "IT新品牌" + ThreadLocalRandom.current().nextLong(1_000_000L);
        byte[] bytes = workbook(new Object[][]{
                {"款号", "款式名称", "大类", "品牌", "品类", "年份", "季节", "尺码组", "颜色", "默认加工厂", "版型库单号"},
                {code, "导入测试款", "服饰", brand, f.leafPath(), "2027", "春季", f.sizeGroupName(), f.colorCode(), shortName, "BX-01"}});

        var imported = styleService.importExcel(new MockMultipartFile("file", "款式.xlsx", Excel.CONTENT_TYPE, bytes));
        fixtureBrandIds.add(jdbcTemplate.queryForObject(
                "SELECT id FROM sys_dict_data WHERE type_code = 'brand' AND label = ? AND deleted = false", Long.class, brand));
        assertThat(imported.total()).isEqualTo(1);

        long id = jdbcTemplate.queryForObject("SELECT id FROM sty_style WHERE code = ?", Long.class, code);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sty_style WHERE id = ?", String.class, id))
                .isEqualTo(StyleStatus.ENABLED.name());
        assertThat(jdbcTemplate.queryForObject("SELECT default_factory_id FROM sty_style WHERE id = ?", Long.class, id))
                .isEqualTo(supplierId);
        assertThat(jdbcTemplate.queryForObject("SELECT legacy_attrs ->> '版型库单号' FROM sty_style WHERE id = ?", String.class, id))
                .isEqualTo("BX-01");
        assertThat(count("SELECT count(*) FROM sty_style_sku WHERE style_id = ?", id)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM sys_dict_data WHERE type_code = 'brand' AND label = ? AND deleted = false", brand))
                .isEqualTo(1);

        // 没有设计师、版师、建档人的款：列表、详情、导出取名字不出空指针
        StyleQuery query = new StyleQuery();
        query.setIds(List.of(id));
        assertThat(styleService.page(query).getList()).singleElement()
                .satisfies(r -> assertThat(r.getDesignerName()).isNull());
        assertThat(styleService.detail(id).getDefaultFactoryName()).isEqualTo(shortName);
        byte[] exported = styleService.export(query);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(book.getSheet("款式").getRow(1).getCell(0).getStringCellValue()).isEqualTo(code);
            assertThat(book.getSheet("导出 SKU").getPhysicalNumberOfRows()).isEqualTo(6);
        }
        BizException again = catchThrowableOfType(BizException.class, () -> styleService.importExcel(
                new MockMultipartFile("file", "款式.xlsx", Excel.CONTENT_TYPE, exported)));
        assertThat(again.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
    }

    // ---------- 造数据 ----------

    private record Fixture(long parentCategoryId, long leafId, String leafName, String leafPath, long sizeGroupId,
                           String sizeGroupName, long designerId, String designerName, String designerNo, String brand,
                           String brandLabel, long colorId, String colorCode) {
    }

    /** 一个新的两级品类、一个 S–XXL 的尺码组、一个颜色、一个有编号的设计师、一个品牌 */
    private Fixture fixture() {
        String suffix = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L), 36);
        String parentName = "IT女装" + suffix;
        String leafName = "IT风衣" + suffix;
        long parentId = jdbcTemplate.queryForObject(
                "INSERT INTO bas_category (parent_id, name, level) VALUES (NULL, ?, 1) RETURNING id", Long.class, parentName);
        long leafId = jdbcTemplate.queryForObject(
                "INSERT INTO bas_category (parent_id, name, level) VALUES (?, ?, 2) RETURNING id", Long.class, parentId, leafName);
        String groupName = "IT字母码" + suffix;
        long groupId = jdbcTemplate.queryForObject(
                "INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id", Long.class, groupName);
        for (int i = 0; i < SIZES.size(); i++) {
            jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, ?, ?)",
                    groupId, i + 1, SIZES.get(i));
        }
        long colorId = color();
        String colorCode = jdbcTemplate.queryForObject("SELECT code FROM bas_color WHERE id = ?", String.class, colorId);
        String designerNo = freeDesignerNo();
        String designerName = "IT设计师" + suffix;
        long designerId = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, designer_no) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it_designer_" + suffix, designerName, designerNo);
        DictDataSaveReq brand = new DictDataSaveReq();
        brand.setTypeCode("brand");
        brand.setLabel("IT品牌" + suffix);
        brand.setValue("ITB" + suffix.toUpperCase());
        fixtureBrandIds.add(dictDataService.create(brand));
        return new Fixture(parentId, leafId, leafName, parentName + "/" + leafName, groupId, groupName, designerId, designerName,
                designerNo, brand.getValue(), brand.getLabel(), colorId, colorCode);
    }

    private StyleCreateReq req(Fixture f, String name) {
        StyleCreateReq req = new StyleCreateReq();
        req.setCodeMode(StyleCreateReq.CodeMode.AUTO);
        req.setName(name);
        req.setStyleClass("FS");
        req.setBrand(f.brand());
        req.setCategoryId(f.leafId());
        req.setYear(2027);
        req.setSeason("SPRING");
        req.setSizeGroupId(f.sizeGroupId());
        req.setDesignerId(f.designerId());
        req.setColorIds(List.of(f.colorId()));
        return req;
    }

    private long color() {
        String code;
        do {
            code = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36), 36).toUpperCase();
        } while (code.length() < 2 || count("SELECT count(*) FROM bas_color WHERE code = ?", code) > 0);
        return jdbcTemplate.queryForObject("INSERT INTO bas_color (code, name) VALUES (?, ?) RETURNING id", Long.class,
                code, "IT色" + code + ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** 01–99 里没人用的编号（删了的人的也算用过） */
    private String freeDesignerNo() {
        for (int i = 0; i < 200; i++) {
            String no = String.format("%02d", ThreadLocalRandom.current().nextInt(1, 100));
            if (count("SELECT count(*) FROM sys_user WHERE designer_no = ?", no) == 0) {
                return no;
            }
        }
        throw new IllegalStateException("01-99 的设计师编号都被占了：清一下 IT 库");
    }

    private long supplier() {
        String code = "ITS" + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        return jdbcTemplate.queryForObject("INSERT INTO sup_supplier (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, code, "IT加工厂全称" + code, "IT加工厂" + code);
    }

    private static StyleSkuSaveReq skuReq(String barcode) {
        StyleSkuSaveReq req = new StyleSkuSaveReq();
        req.setBarcode(barcode);
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

    /** 一张"数据"表，全是文字格子 */
    private static byte[] workbook(Object[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
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
