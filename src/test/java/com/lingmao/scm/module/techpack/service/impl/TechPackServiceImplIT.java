package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.excel.ExcelPreview;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomImportVO;
import com.lingmao.scm.module.techpack.dto.BomItemForm;
import com.lingmao.scm.module.techpack.dto.BomSkuForm;
import com.lingmao.scm.module.techpack.dto.PackFileForm;
import com.lingmao.scm.module.techpack.dto.PackFileVO;
import com.lingmao.scm.module.techpack.dto.PublishProblem;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostForm;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.CostMode;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LoginUser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资料包在真库上：整版保存、乐观锁、生效时旧版作废、回写款式的资料包状态、一个阶段只能有一个草稿（部分唯一索引）、
 * 删草稿连明细一起删（外键是 RESTRICT，删的顺序错了会报错）、工艺单文件跟着修订走、Excel 预览、从 BOM 表导入
 */
@IntegrationTest
class TechPackServiceImplIT {

    private static final long USER = 900_201L;

    @Autowired
    private TechPackService techPackService;
    @Autowired
    private TechPackCostService costService;
    @Value("${lingmao.upload.dir}")
    private String uploadDir;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.lingmao.scm.module.system.cache.DictCache dictCache;

    @BeforeEach
    void setUp() {
        TestIdentity.ensureUser(jdbcTemplate, USER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(USER, "jti-it"), null, Stream.of("techpack:pack:list", "techpack:pack:add", "techpack:pack:edit",
                        "techpack:pack:remove", "techpack:pack:publish", "techpack:cost:query", "techpack:bom:dev", "techpack:bom:prod")
                .map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（docs/api/01 §3.4-3.8）：生产 BOM 草稿保存 → 生效（款式变"已生效"）→ 修订出 V2，没写本版说明生效被拦，写了生效、V1 作废。
     * 保存时 versionLock 对不上 → 1002。没有研发 BOM、转生产 BOM（甲方 11-18 答 Q24）
     */
    @Test
    void prodFlow() {
        Fixture f = fixture();
        long prod = techPackService.create(f.style);
        TechPackDetailVO draft = techPackService.detail(prod);
        assertThat(draft.getStatus()).isEqualTo(PackStatus.DRAFT);
        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getFiles()).isEmpty();
        assertThat(draft.getBomItems()).isEmpty();
        assertThat(draft.getActions()).containsExactly("edit", "publish", "remove");

        int lock = techPackService.save(prod, saveReq(draft.getVersionLock(), f, null));
        assertThat(lock).isEqualTo(draft.getVersionLock() + 1);
        assertThatThrownBy(() -> techPackService.save(prod, saveReq(draft.getVersionLock(), f, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        TechPackDetailVO saved = techPackService.detail(prod);
        assertThat(saved.getBomItems()).hasSize(1);
        assertThat(saved.getBomItems().getFirst().getSkus()).hasSize(2);
        assertThat(saved.getBomItems().getFirst().getUnit()).isEqualTo("METER");
        // 单件成本 = 1.5 ×（1 + 3%）× 18.5 = 28.58
        assertThat(saved.getBomItems().getFirst().getUnitCost()).isEqualByComparingTo("28.58");

        assertThat(techPackStatus(f.style)).isEqualTo("NONE");
        techPackService.publish(prod, lock);
        assertThat(techPackStatus(f.style)).isEqualTo("PROD");
        TechPackDetailVO published = techPackService.detail(prod);
        assertThat(published.getStage()).isEqualTo(PackStage.PROD);
        assertThat(published.getActions()).containsExactly("revise");

        TechPackOverviewVO overview = techPackService.overview(f.style);
        assertThat(overview.getDefaultPackId()).isEqualTo(prod);
        assertThat(overview.getUnitCostPackId()).isEqualTo(prod);
        assertThat(overview.isCreatable()).isTrue();   // 没有草稿就能建（docs/api/01 §3.2）；页面上有版本时只给"修订"
        TechPackCostVO cost = costService.cost(prod, null);
        assertThat(cost.getSummary().total()).isEqualByComparingTo(overview.getUnitCost());
        // 按款看成本取的是生产已生效版，数和核价页签一样（PRD §6.4 验收 10）
        StyleCostQuery query = new StyleCostQuery();
        query.setKeyword(styleCode(f.style));
        List<StyleCostRowVO> rows = costService.styleCosts(query).getList();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getTechPackId()).isEqualTo(prod);
            assertThat(r.getStage()).isEqualTo(PackStage.PROD);
            assertThat(r.getTotal()).isEqualByComparingTo(cost.getSummary().total());
        });
        query.setKeyword(styleCode(f.style).toLowerCase() + "，NO-SUCH-STYLE");
        assertThat(costService.styleCosts(query).getList()).extracting(StyleCostRowVO::getTechPackId).containsExactly(prod);

        long v2 = techPackService.revise(prod);
        TechPackDetailVO v2Draft = techPackService.detail(v2);
        assertThat(v2Draft.getVersion()).isEqualTo(2);
        assertThat(techPackService.overview(f.style).isCreatable()).isFalse();   // 已经有草稿
        assertThatThrownBy(() -> techPackService.publish(v2, v2Draft.getVersionLock()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getData()).isEqualTo(List.of(new PublishProblem(0, "V2 起要写本版说明")));
                });

        int v2Lock = techPackService.save(v2, saveReq(v2Draft.getVersionLock(), f, "袖长改短 1cm"));
        techPackService.publish(v2, v2Lock);
        assertThat(status(prod)).isEqualTo("VOIDED");
        assertThat(status(v2)).isEqualTo("EFFECTIVE");
    }

    @Test
    void publishedContextFreezesColorsSizesAndOwner_legacyIsExplicit() throws Exception {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        int lock = techPackService.save(id, saveReq(0, f, null));
        techPackService.publish(id, lock);
        TechPackDetailVO original = techPackService.detail(id);
        assertThat(original.getContextSource()).isEqualTo("SNAPSHOT");
        assertThat(jdbcTemplate.queryForObject("SELECT owner_snapshot->>'schemaVersion' FROM tp_tech_pack WHERE id=?", String.class, id))
                .isEqualTo("1");
        jdbcTemplate.update("UPDATE sty_style_color SET status=0 WHERE style_id=? AND color_id=?", f.style, f.colorB);
        jdbcTemplate.update("UPDATE bas_color SET name='改名后的颜色' WHERE id=?", f.colorA);
        jdbcTemplate.update("UPDATE sty_style SET name='改名后的款式' WHERE id=?", f.style);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) SELECT size_group_id, 4, 'XL' FROM sty_style WHERE id=?", f.style);
        TechPackDetailVO frozen = techPackService.detail(id);
        assertThat(frozen.getColors()).isEqualTo(original.getColors());
        assertThat(frozen.getSizes()).containsExactly("S", "M", "L");
        assertThat(frozen.getStyleName()).isEqualTo(original.getStyleName());
        // 模拟 V25 旧版本：不能伪装成冻结快照，停用的颜色照样列出来
        jdbcTemplate.update("UPDATE tp_tech_pack SET owner_snapshot=NULL WHERE id=?", id);
        TechPackDetailVO legacy = techPackService.detail(id);
        assertThat(legacy.getContextSource()).isEqualTo("LEGACY");
        assertThat(legacy.getColors()).extracting(c -> c.id()).contains(f.colorB);
    }

    /**
     * 规则（RFC 012 §5.4）：复制成新款。新款照请求建（款号手填的新号、尺码组换成 M、L）；老款已生效的资料包复制成新款的 V1 草稿，
     * 物料清单、加工费带过去，记一条"从 老款号 V1 复制"
     */
    @Test
    void copyStyle_createsStyleAndDraftPack() {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        int lock = techPackService.save(id, saveReq(0, f, null));
        techPackService.publish(id, lock);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 复制尺码" + suffix());
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')", sizeGroup, sizeGroup);
        var old = jdbcTemplate.queryForMap("SELECT code, category_id FROM sty_style WHERE id = ?", f.style);
        var req = new com.lingmao.scm.module.style.dto.StyleCreateReq();
        req.setCodeMode(com.lingmao.scm.module.style.dto.StyleCreateReq.CodeMode.MANUAL);
        req.setCode("ITCP" + suffix());
        req.setName("IT 复制的款");
        req.setStyleClass("FS");
        String brand = "IT 复制品牌" + suffix();
        req.setBrand(brand);
        req.setCategoryId(((Number) old.get("category_id")).longValue());
        req.setYear(2027);
        req.setSeason("AUTUMN");
        req.setSource("OWN");
        req.setSizeGroupId(sizeGroup);
        req.setColorIds(List.of(f.colorA, f.colorB));

        // 品牌字典迁移要求是空的（SchemaConventionsIT），用完物理删掉
        long brandId = jdbcTemplate.queryForObject("INSERT INTO sys_dict_data (type_code, label, value, sort) VALUES ('brand', ?, ?, 99) "
                + "RETURNING id", Long.class, brand, brand);
        com.lingmao.scm.module.style.dto.StyleCreateResult created;
        try {
            dictCache.evictNow(List.of("brand"));
            created = techPackService.copyStyle(f.style, req);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_dict_data WHERE id = ?", brandId);
            dictCache.evictNow(List.of("brand"));
        }

        assertThat(created.code()).isEqualTo(req.getCode());
        long copied = jdbcTemplate.queryForObject("SELECT id FROM tp_tech_pack WHERE style_id = ? AND status = 'DRAFT' AND version = 1",
                Long.class, created.id());
        TechPackDetailVO detail = techPackService.detail(copied);
        assertThat(detail.getBomItems()).singleElement().satisfies(b -> {
            assertThat(b.getMaterialId()).isEqualTo(f.material);
            assertThat(b.getSkus()).hasSize(2);
        });
        assertThat(jdbcTemplate.queryForObject("SELECT remark FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ?", String.class, copied))
                .isEqualTo("从 " + old.get("code") + " V1 复制");
        assertThat(count("SELECT count(*) FROM tp_tech_pack WHERE style_id = ? AND status = 'EFFECTIVE'", f.style)).isEqualTo(1);
    }

    /**
     * 规则（RFC 012 §3，V42）：包材归生产填。只能填生产的人能加包材的行，面料的行原样带回能存（行 id 带回来），
     * 改面料用量 403、什么都不写
     */
    @Test
    void bomSplit_productionOnlyAddsPacking_cannotTouchFabric() {
        assertThat(jdbcTemplate.queryForObject("SELECT text_value FROM sys_dict_data WHERE type_code = 'material_type' AND value = 'PACKING' "
                + "AND deleted = false", String.class)).isEqualTo("PROD");
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        int version = techPackService.save(id, saveReq(0, f, null));
        long packing = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                SELECT code || 'P', 'IT 资料包胶袋', category_id, 'PACKING', 'METER', 'METER' FROM bas_material WHERE id = ? RETURNING id""",
                Long.class, f.material);
        long packingSku = jdbcTemplate.queryForObject("INSERT INTO bas_material_sku (material_id, code) SELECT id, code FROM bas_material "
                + "WHERE id = ? RETURNING id", Long.class, packing);
        long fabricRow = jdbcTemplate.queryForObject("SELECT id FROM tp_bom_item WHERE tech_pack_id = ?", Long.class, id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(USER, "test"), null,
                Stream.of("techpack:pack:edit", "techpack:bom:prod", "techpack:cost:query").map(SimpleGrantedAuthority::new).toList()));

        TechPackSaveReq touched = saveReq(version, f, null);
        touched.getBomItems().getFirst().setId(fabricRow);
        touched.getBomItems().getFirst().setUsageQty(new BigDecimal("2"));
        assertThatThrownBy(() -> techPackService.save(id, touched)).isInstanceOf(com.lingmao.scm.security.FieldPermissionDenied.class);
        assertThat(techPackService.detail(id).getVersionLock()).isEqualTo(version);

        TechPackSaveReq added = saveReq(version, f, null);
        added.getBomItems().getFirst().setId(fabricRow);
        BomItemForm bag = new BomItemForm();
        bag.setPart("包装");
        bag.setMaterialId(packing);
        bag.setSkuMode(BomSkuMode.FIXED);
        bag.setUsageMode(BomUsageMode.UNIFORM);
        bag.setUsageQty(BigDecimal.ONE);
        bag.setSkus(List.of(skuForm(f.colorA, packingSku)));
        added.setBomItems(List.of(added.getBomItems().getFirst(), bag));
        techPackService.save(id, added);
        assertThat(techPackService.detail(id).getBomItems()).extracting(b -> b.getMaterialId()).containsExactly(f.material, packing);
    }

    @Test
    void costAuthorizationPreservesHeadAndChildren() {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        TechPackSaveReq initial = saveReq(0, f, null);
        var cost = new com.lingmao.scm.module.techpack.dto.TechPackCostForm();
        cost.setCostMode(com.lingmao.scm.module.techpack.entity.CostMode.BUY); cost.setBuyPrice(new BigDecimal("30"));
        var fee = new com.lingmao.scm.module.techpack.dto.CostItemForm(); fee.setName("包装"); fee.setAmount(new BigDecimal("2"));
        cost.setItems(List.of(fee)); initial.setCost(cost);
        int version = techPackService.save(id, initial);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(USER, "test"), null,
                Stream.of("techpack:pack:edit", "techpack:bom:dev", "techpack:bom:prod").map(SimpleGrantedAuthority::new).toList()));
        TechPackSaveReq denied = saveReq(version, f, "仅改说明");
        var empty = new com.lingmao.scm.module.techpack.dto.TechPackCostForm();
        empty.setCostMode(com.lingmao.scm.module.techpack.entity.CostMode.OUTSOURCE); empty.setItems(List.of()); denied.setCost(empty);
        assertThatThrownBy(() -> techPackService.save(id, denied))
                .isInstanceOf(com.lingmao.scm.security.FieldPermissionDenied.class);
        assertThat(techPackService.detail(id).getVersionLock()).isEqualTo(version);
        TechPackSaveReq hidden = saveReq(version, f, "仅改说明");
        hidden.getBomItems().getFirst().setRefPrice(null);
        int afterDenied = techPackService.save(id, hidden);
        assertThat(jdbcTemplate.queryForObject("SELECT buy_price FROM tp_tech_pack WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo("30");
        assertThat(count("SELECT COUNT(*) FROM tp_cost_item WHERE tech_pack_id=?", id)).isEqualTo(1);
        setUp();
        int afterNull = techPackService.save(id, saveReq(afterDenied, f, "成本未加载"));
        assertThat(count("SELECT COUNT(*) FROM tp_cost_item WHERE tech_pack_id=?", id)).isEqualTo(1);
        TechPackSaveReq clear = saveReq(afterNull, f, "明确清空成本"); clear.setCost(empty);
        techPackService.save(id, clear);
        assertThat(jdbcTemplate.queryForObject("SELECT buy_price FROM tp_tech_pack WHERE id=?", BigDecimal.class, id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM tp_cost_item WHERE tech_pack_id=?", id)).isZero();
    }

    /** 规则：同款已经有草稿再建 → 1000；绕过检查直接插第二个草稿，部分唯一索引 uk_tp_pack_draft 拦住 */
    @Test
    void oneDraftPerStyle() {
        Fixture f = fixture();
        techPackService.create(f.style);
        assertThat(techPackService.overview(f.style).isCreatable()).isFalse();
        assertThatThrownBy(() -> techPackService.create(f.style))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("已经有草稿"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 99, 'DRAFT')", f.style))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** 规则：删草稿连同配料、分码用量、BOM 行、文件的引用一起删；已生效的不能删（1002） */
    @Test
    void deleteDraftWithChildren() {
        Fixture f = fixture();
        long other = techPackService.create(f.style);
        techPackService.save(other, saveReq(0, f, null));
        techPackService.publish(other, 1);
        long dev = techPackService.create(f.style);
        TechPackSaveReq withFile = saveReq(0, f, null);
        withFile.setFiles(List.of(file("CRAFT_SHEET", "/files/it-techpack/gone-" + suffix() + ".pdf", "工艺单.pdf")));
        int lock = techPackService.save(dev, withFile);

        int historyBefore = count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ?", dev);
        assertThat(historyBefore).isGreaterThan(0);
        techPackService.delete(dev);
        assertThat(count("SELECT count(*) FROM tp_tech_pack WHERE id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM tp_bom_item WHERE tech_pack_id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM biz_attachment WHERE doc_type = 'TECH_PACK' AND doc_id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ?", dev)).isEqualTo(historyBefore);
        assertThat(count("SELECT count(*) FROM sys_data_log WHERE table_name = 'tp_tech_pack' AND row_id = ? AND action = 'DELETE'", dev)).isEqualTo(1);
        assertThat(lock).isEqualTo(1);

        assertThatThrownBy(() -> techPackService.delete(other))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        assertThat(count("SELECT count(*) FROM tp_bom_item WHERE tech_pack_id = ?", other)).isEqualTo(1);
    }

    /** 规则（docs/api/01 §3.9）：价格档案里没有取 SKU 单价；有了取档案的现价。换算成元 / 库存单位，供应商取物料的默认供应商 */
    @Test
    void bomDefaults_archiveBeforeSkuPrice() {
        Fixture f = fixture();
        long prod = techPackService.create(f.style);
        BomDefaultReq req = new BomDefaultReq();
        req.setMaterialId(f.material);
        req.setSkuIds(List.of(f.skuA));
        BomDefaultVO d = techPackService.bomDefaults(prod, List.of(req)).getFirst();
        assertThat(d.getSupplierId()).isEqualTo(f.supplier);
        assertThat(d.getRefPrice()).isEqualByComparingTo("18.5");
        assertThat(d.getPriceFrom()).isEqualTo(BomPricing.FROM_SKU);
        assertThat(d.getLossPct()).isEqualByComparingTo("3");

        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, 99, 'METER', 13, DATE '2026-01-01')""", f.supplier, f.material);
        BomDefaultVO p = techPackService.bomDefaults(prod, List.of(req)).getFirst();
        assertThat(p.getRefPrice()).isEqualByComparingTo("99");
        assertThat(p.getPriceFrom()).isEqualTo(BomPricing.FROM_ARCHIVE);
    }

    /**
     * 规则（RFC 011 §2）：工艺单页签的文件按组整组保存，详情里按组排；每组收的类型不一样，放错组 1000、原来的不动；
     * Excel 能在页面里看（读出 sheet 和格子），别的类型 1000；修订带到新版本（只抄引用，文件还是那一个）
     */
    @Test
    void files_groupsPreviewAndRevise() throws Exception {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("it-techpack");
        Files.createDirectories(dir);
        Path xlsx = dir.resolve("craft-" + suffix() + ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet sheet = wb.createSheet("工艺");
            sheet.createRow(0).createCell(0).setCellValue("缝制要求");
            sheet.createRow(1).createCell(1).setCellValue("针距 3cm 12 针");
            wb.write(out);
        }
        try {
            TechPackSaveReq req = saveReq(0, f, null);
            req.setFiles(List.of(file("PATTERN", "/files/it-techpack/p-" + suffix() + ".prj", "纸样.prj"),
                    file("CRAFT_SHEET", "/files/it-techpack/" + xlsx.getFileName(), "工艺单.xlsx")));
            int lock = techPackService.save(id, req);
            List<PackFileVO> files = techPackService.detail(id).getFiles();
            assertThat(files).extracting(PackFileVO::category).containsExactly("CRAFT_SHEET", "PATTERN");
            assertThat(files.getFirst().url()).contains("/files/it-techpack/" + xlsx.getFileName());

            ExcelPreview.Book book = techPackService.preview(id, files.getFirst().id());
            assertThat(book.sheets()).singleElement().satisfies(s -> {
                assertThat(s.name()).isEqualTo("工艺");
                assertThat(s.cells()).extracting(ExcelPreview.CellView::v).contains("缝制要求", "针距 3cm 12 针");
            });
            assertThatThrownBy(() -> techPackService.preview(id, files.get(1).id()))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR));

            TechPackSaveReq wrong = saveReq(lock, f, null);
            wrong.setFiles(List.of(file("CRAFT_SHEET", "/files/it-techpack/p.prj", "纸样.prj")));
            assertThatThrownBy(() -> techPackService.save(id, wrong)).isInstanceOf(BizException.class).hasMessageContaining("不收 .prj");
            assertThat(techPackService.files(id)).hasSize(2);

            techPackService.publish(id, lock);
            long v2 = techPackService.revise(id);
            assertThat(techPackService.files(v2)).extracting(PackFileVO::name).containsExactly("工艺单.xlsx", "纸样.prj");
        } finally {
            Files.deleteIfExists(xlsx);
        }
    }

    /**
     * 规则（RFC 011 §4）：BOM 表按款号认 sheet，物料按名称对上物料库，写了几个颜色 → 随款色、每个颜色配同色 SKU；
     * 损耗整列是系数 1.03 → 3%，A 料 → 大身，"含税运"换成两个标记，加工费、运费从汇总块读；物料库里没有的 UNMATCHED，
     * 人工挑了物料（picks）再认一次就对上了。只解析不写库；不是草稿 1002
     */
    @Test
    void bomImport_matchesAndPicks() {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        String code = styleCode(f.style);
        String colorA = jdbcTemplate.queryForObject("SELECT name FROM bas_color WHERE id = ?", String.class, f.colorA);
        String colorB = jdbcTemplate.queryForObject("SELECT name FROM bas_color WHERE id = ?", String.class, f.colorB);
        String supplier = jdbcTemplate.queryForObject("SELECT short_name FROM sup_supplier WHERE id = ?", String.class, f.supplier);
        // 每个用例的物料都叫"IT 资料包面料"：改个这一次独有的名字，不然同名的有好几个
        String fabricName = "IT 导入面料" + suffix();
        jdbcTemplate.update("UPDATE bas_material SET name = ? WHERE id = ?", fabricName, f.material);
        byte[] bom = workbook(wb -> {
            Sheet other = wb.createSheet("别的款");
            other.createRow(0).createCell(0).setCellValue("X999 别的款 BOM");
            header(other.createRow(1));
            Sheet sheet = wb.createSheet("本款");
            sheet.createRow(0).createCell(0).setCellValue(code + " IT 资料包款 BOM");
            header(sheet.createRow(1));
            cells(sheet.createRow(2), "A料", "面料A", fabricName, "", colorA + "、" + colorB, 20, "米", 1.2, 1.03, 24.72, supplier, "含税运");
            cells(sheet.createRow(3), "辅料", "辅料", "IT 不存在的扣子", "", "/", 0.5, "个", 2, 1.0, 1, "", "");
            cells(sheet.createRow(5), "加工费", "", "", "", "", null, "", null, null, 25.5, "", "");
            cells(sheet.createRow(6), "运费：", "", "", "", "", null, "", null, null, 1.2, "", "");
            wb.createSheet("说明").createRow(0).createCell(0).setCellValue("改版记录");
        });

        BomImportVO parsed = techPackService.bomImport(id, new ByteArrayInputStream(bom), null, Map.of());
        assertThat(parsed.sheet()).isEqualTo(1);
        assertThat(parsed.sheets()).extracting(BomImportVO.Sheet::bom).containsExactly(true, true, false);
        assertThat(parsed.laborPrice()).isEqualByComparingTo("25.5");
        assertThat(parsed.fees()).singleElement().satisfies(fee -> {
            assertThat(fee.name()).isEqualTo("运费");
            assertThat(fee.amount()).isEqualByComparingTo("1.2");
        });
        assertThat(parsed.rows()).hasSize(2);
        BomImportVO.Row fabric = parsed.rows().getFirst();
        assertThat(fabric.line()).isEqualTo(3);
        assertThat(fabric.status()).as(fabric.notes().toString()).isEqualTo("OK");
        assertThat(fabric.item().getMaterialId()).isEqualTo(f.material);
        assertThat(fabric.item().getPart()).isEqualTo("BODY");
        assertThat(fabric.item().getSkuMode()).isEqualTo(BomSkuMode.BY_COLOR);
        assertThat(fabric.item().getSkus()).extracting(s -> s.getMaterialSkuId()).containsExactly(f.skuA, f.skuB);
        assertThat(fabric.item().getLossPct()).isEqualByComparingTo("3");
        assertThat(fabric.item().getUsageQty()).isEqualByComparingTo("1.2");
        assertThat(fabric.item().getRefPrice()).isEqualByComparingTo("20");
        assertThat(fabric.item().getSupplierId()).isEqualTo(f.supplier);
        assertThat(fabric.item().getPriceTaxIncluded()).isTrue();
        assertThat(fabric.item().getPriceFreightIncluded()).isTrue();
        BomImportVO.Row button = parsed.rows().get(1);
        assertThat(button.status()).isEqualTo("UNMATCHED");
        assertThat(button.item()).isNull();
        assertThat(button.source().name()).isEqualTo("IT 不存在的扣子");

        BomImportVO picked = techPackService.bomImport(id, new ByteArrayInputStream(bom), 1, Map.of(4, f.material));
        assertThat(picked.rows().get(1).item()).isNotNull();
        assertThat(picked.rows().get(1).status()).isEqualTo("CHECK");   // 单位是个，物料按米管：要人看
        assertThat(count("SELECT count(*) FROM tp_bom_item WHERE tech_pack_id = ?", id)).isZero();

        assertThatThrownBy(() -> techPackService.bomImport(id, new ByteArrayInputStream(bom), 2, Map.of()))
                .isInstanceOf(BizException.class).hasMessageContaining("不是物料清单");
        assertThatThrownBy(() -> techPackService.bomImport(id, new ByteArrayInputStream("不是表".getBytes()), null, Map.of()))
                .isInstanceOf(BizException.class);
        techPackService.publish(id, techPackService.save(id, saveReq(0, f, null)));
        assertThatThrownBy(() -> techPackService.bomImport(id, new ByteArrayInputStream(bom), null, Map.of()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
    }

    /** 规则（RFC 011 §3.1、Q50）：加工费和备注存在资料包上，按外发算的核价用它；排单取锁定版本的加工费 */
    @Test
    void laborPrice_savedAndUsed() {
        Fixture f = fixture();
        long id = techPackService.create(f.style);
        TechPackSaveReq req = saveReq(0, f, null);
        TechPackCostForm cost = new TechPackCostForm();
        cost.setCostMode(CostMode.OUTSOURCE);
        cost.setLaborPrice(new BigDecimal("25.5"));
        cost.setLaborNote("  清加工 25.5  ");
        cost.setItems(List.of());
        req.setCost(cost);
        techPackService.save(id, req);
        TechPackCostVO vo = costService.cost(id, null);
        assertThat(vo.getLaborPrice()).isEqualByComparingTo("25.5");
        assertThat(vo.getSummary().labor()).isEqualByComparingTo("25.5");
        assertThat(vo.getLaborNote()).isEqualTo("清加工 25.5");
        assertThat(techPackService.laborPrices(List.of(id))).containsEntry(id, new BigDecimal("25.5000"));
    }

    // ---------------------------------------------------------------- 数据

    /** 一个款式（两个颜色、S/M/L 基码 M）、一个面料（两个颜色的 SKU，SKU 价 18.5）、一家供应商 */
    private record Fixture(long style, long colorA, long colorB, long material, long skuA, long skuB, long supplier) {
    }

    private Fixture fixture() {
        String s = suffix();
        long colorA = TestColors.insert(jdbcTemplate, "IT 甲色" + s);
        long colorB = TestColors.insert(jdbcTemplate, "IT 乙色" + s);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 资料包尺码" + s);
        List<String> sizes = List.of("S", "M", "L");
        for (int i = 0; i < sizes.size(); i++) {
            jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, ?, ?)", sizeGroup, i + 1,
                    sizes.get(i));
        }
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 资料包品类" + s);
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price)
                VALUES (?, 'IT 资料包款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299) RETURNING id""",
                Long.class, "ITTP" + s, category, sizeGroup);
        jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1), (?, ?, 2)", style, colorA, style, colorB);
        long supplier = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, tax_pct) VALUES (?, ?, ?, 'ACTIVE', 13) RETURNING id""",
                Long.class, "ITTP" + s, "IT 资料包面料厂" + s, "IT 面料厂" + s);
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 资料包分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, default_supplier_id,
                                          ref_price, default_loss_pct)
                VALUES (?, 'IT 资料包面料', ?, 'FABRIC', 'METER', 'METER', ?, 16, 3) RETURNING id""",
                Long.class, "ITTP" + s, materialCategory, supplier);
        long skuA = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material_sku (material_id, code, color_id, price) VALUES (?, ?, ?, 18.5) RETURNING id""",
                Long.class, material, "ITTP" + s + "-A", colorA);
        long skuB = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material_sku (material_id, code, color_id, price) VALUES (?, ?, ?, 18.5) RETURNING id""",
                Long.class, material, "ITTP" + s + "-B", colorB);
        return new Fixture(style, colorA, colorB, material, skuA, skuB, supplier);
    }

    /** 一行随款色的面料，统一用量 1.5、损耗 3%、单价 18.5，两个颜色各配一个 SKU */
    private static TechPackSaveReq saveReq(int versionLock, Fixture f, String note) {
        BomItemForm item = new BomItemForm();
        item.setPart("大身");
        item.setMaterialId(f.material);
        item.setSkuMode(BomSkuMode.BY_COLOR);
        item.setUsageMode(BomUsageMode.UNIFORM);
        item.setUsageQty(new BigDecimal("1.5"));
        item.setLossPct(new BigDecimal("3"));
        item.setSource(MaterialSource.PURCHASE);
        item.setSupplierId(f.supplier);
        item.setRefPrice(new BigDecimal("18.5"));
        item.setSkus(List.of(skuForm(f.colorA, f.skuA), skuForm(f.colorB, f.skuB)));
        TechPackSaveReq req = new TechPackSaveReq();
        req.setVersionLock(versionLock);
        req.setVersionNote(note);
        req.setBomItems(List.of(item));
        return req;
    }

    private static PackFileForm file(String category, String url, String name) {
        PackFileForm form = new PackFileForm();
        form.setCategory(category);
        form.setUrl(url);
        form.setName(name);
        form.setSize(100L);
        return form;
    }

    /** 甲方 BOM 表的表头（列的顺序和原件一样：项目、类别没有表头字） */
    private static void header(Row row) {
        cells(row, "项目", "", "物料名称", "规格", "颜色", "单价", "单位", "单件用量", "损耗", "预估价", "工厂", "备注");
    }

    /** 一行格子：数字写成数字格，null 和 "" 留空 */
    private static void cells(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v instanceof Number n) {
                row.createCell(i).setCellValue(n.doubleValue());
            } else if (v instanceof String t && !t.isEmpty()) {
                row.createCell(i).setCellValue(t);
            }
        }
    }

    private static byte[] workbook(java.util.function.Consumer<XSSFWorkbook> body) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            body.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static BomSkuForm skuForm(long colorId, long skuId) {
        BomSkuForm form = new BomSkuForm();
        form.setColorId(colorId);
        form.setMaterialSkuId(skuId);
        return form;
    }

    private String styleCode(long style) {
        return jdbcTemplate.queryForObject("SELECT code FROM sty_style WHERE id = ?", String.class, style);
    }

    private String techPackStatus(long style) {
        return jdbcTemplate.queryForObject("SELECT tech_pack_status FROM sty_style WHERE id = ?", String.class, style);
    }

    private String status(long pack) {
        return jdbcTemplate.queryForObject("SELECT status FROM tp_tech_pack WHERE id = ?", String.class, pack);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String suffix() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
    }

}
