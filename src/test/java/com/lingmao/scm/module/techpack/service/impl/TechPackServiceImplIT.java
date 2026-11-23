package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomItemForm;
import com.lingmao.scm.module.techpack.dto.BomSkuForm;
import com.lingmao.scm.module.techpack.dto.CraftSectionForm;
import com.lingmao.scm.module.techpack.dto.CraftTextsReq;
import com.lingmao.scm.module.techpack.dto.PointOption;
import com.lingmao.scm.module.techpack.dto.ProcessForm;
import com.lingmao.scm.module.techpack.dto.PublishProblem;
import com.lingmao.scm.module.techpack.dto.SpecPointForm;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.TechPackPdfService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LoginUser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资料包在真库上：整版保存、乐观锁、生效时旧版作废、回写款式的资料包状态、转生产 BOM 重新取价、一个阶段只能有一个草稿（部分唯一索引）、
 * 删草稿连明细一起删（外键是 RESTRICT，删的顺序错了会报错）
 */
@IntegrationTest
class TechPackServiceImplIT {

    private static final long USER = 900_201L;

    @Autowired
    private TechPackService techPackService;
    @Autowired
    private TechPackCostService costService;
    @Autowired
    private CraftStandardService craftStandardService;
    @Autowired
    private TechPackPdfService pdfService;
    @Value("${lingmao.upload.dir}")
    private String uploadDir;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestIdentity.ensureUser(jdbcTemplate, USER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(USER, "jti-it"), null, Stream.of("techpack:pack:list", "techpack:pack:add", "techpack:pack:edit",
                        "techpack:pack:remove", "techpack:pack:publish", "techpack:cost:query")
                .map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（docs/api/01 §3.4-3.8）：研发草稿保存 → 生效（款式变"研发中"）→ 转生产 BOM（单价按价格档案重新取、记来源）→ 生效（款式变"已生效"）
     * → 修订出 V2，没写本版说明生效被拦。保存时 versionLock 对不上 → 1002
     */
    @Test
    void devToProdFlow() {
        Fixture f = fixture();
        long dev = techPackService.create(f.style, PackStage.DEV);
        TechPackDetailVO draft = techPackService.detail(dev);
        assertThat(draft.getStatus()).isEqualTo(PackStatus.DRAFT);
        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getCraftSections()).hasSize(CraftSection.values().length);
        assertThat(draft.getActions()).containsExactly("edit", "publish", "remove");

        int lock = techPackService.save(dev, saveReq(draft.getVersionLock(), f, null));
        assertThat(lock).isEqualTo(draft.getVersionLock() + 1);
        assertThatThrownBy(() -> techPackService.save(dev, saveReq(draft.getVersionLock(), f, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        TechPackDetailVO saved = techPackService.detail(dev);
        assertThat(saved.getBomItems()).hasSize(1);
        assertThat(saved.getBomItems().getFirst().getSkus()).hasSize(2);
        assertThat(saved.getBomItems().getFirst().getUnit()).isEqualTo("METER");
        // 单件成本 = 1.5 ×（1 + 3%）× 18.5 = 28.58
        assertThat(saved.getBomItems().getFirst().getUnitCost()).isEqualByComparingTo("28.58");

        techPackService.publish(dev, lock);
        assertThat(techPackStatus(f.style)).isEqualTo("DEV");
        assertThat(techPackService.detail(dev).getActions()).containsExactly("toProd", "revise");

        // 价格档案里这家供应商这个 SKU 现价 20：转生产 BOM 后行上取它
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, material_sku_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, ?, 20, 'METER', 13, DATE '2026-01-01')""", f.supplier, f.material, f.skuA);
        long prod = techPackService.toProd(dev);
        TechPackDetailVO prodDraft = techPackService.detail(prod);
        assertThat(prodDraft.getStage()).isEqualTo(PackStage.PROD);
        assertThat(prodDraft.getVersion()).isEqualTo(1);
        assertThat(prodDraft.getSourcePackId()).isEqualTo(dev);
        assertThat(prodDraft.getBomItems().getFirst().getRefPrice()).isEqualByComparingTo("20");
        assertThat(prodDraft.getBomItems().getFirst().getPriceSourceId()).isNotNull();

        techPackService.publish(prod, prodDraft.getVersionLock());
        assertThat(techPackStatus(f.style)).isEqualTo("PROD");
        TechPackOverviewVO overview = techPackService.overview(f.style);
        assertThat(overview.getDefaultPackId()).isEqualTo(prod);
        assertThat(overview.getUnitCostPackId()).isEqualTo(prod);
        TechPackCostVO cost = costService.cost(prod, null);
        assertThat(cost.getDev()).isNotNull();
        assertThat(cost.getDev().packId()).isEqualTo(dev);
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
        assertThat(v2Draft.getSourcePackId()).isEqualTo(dev);
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
        long id = techPackService.create(f.style, PackStage.PROD);
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
        String pdf = flat(pdfService.export(id, f.colorB, false, false).content());
        assertThat(pdf).contains("IT资料包款", "IT乙色").doesNotContain("改名后的款式", "改名后的颜色");
        // 模拟 V25 旧版本：不能伪装成冻结快照，停用颜色仍可打印。
        jdbcTemplate.update("UPDATE tp_tech_pack SET owner_snapshot=NULL WHERE id=?", id);
        TechPackDetailVO legacy = techPackService.detail(id);
        assertThat(legacy.getContextSource()).isEqualTo("LEGACY");
        assertThat(legacy.getColors()).extracting(c -> c.id()).contains(f.colorB);
        assertThat(flat(pdfService.export(id, f.colorB, false, false).content())).contains("IT乙色");
    }

    @Test
    void costAuthorizationPreservesHeadAndChildren() {
        Fixture f = fixture();
        long id = techPackService.create(f.style, PackStage.PROD);
        TechPackSaveReq initial = saveReq(0, f, null);
        var cost = new com.lingmao.scm.module.techpack.dto.TechPackCostForm();
        cost.setCostMode(com.lingmao.scm.module.techpack.entity.CostMode.BUY); cost.setBuyPrice(new BigDecimal("30"));
        var fee = new com.lingmao.scm.module.techpack.dto.CostItemForm(); fee.setName("包装"); fee.setAmount(new BigDecimal("2"));
        cost.setItems(List.of(fee)); initial.setCost(cost);
        int version = techPackService.save(id, initial);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(USER, "test"), null,
                List.of(new SimpleGrantedAuthority("techpack:pack:edit"))));
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

    /** 规则：同款同阶段已经有草稿再建 → 1000；绕过检查直接插第二个草稿，部分唯一索引 uk_tp_pack_draft 拦住 */
    @Test
    void oneDraftPerStage() {
        Fixture f = fixture();
        long dev = techPackService.create(f.style, PackStage.DEV);
        assertThatThrownBy(() -> techPackService.create(f.style, PackStage.DEV))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("已经有草稿"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'DEV', 99, 'DRAFT')", f.style))
                .isInstanceOf(DuplicateKeyException.class);
        // 生产阶段不受研发草稿影响
        assertThat(techPackService.create(f.style, PackStage.PROD)).isNotEqualTo(dev);
    }

    /** 规则：删草稿连同配料、分码用量、BOM 行、工艺文字一起删；已生效的不能删（1002） */
    @Test
    void deleteDraftWithChildren() {
        Fixture f = fixture();
        long dev = techPackService.create(f.style, PackStage.DEV);
        int lock = techPackService.save(dev, saveReq(0, f, null));
        long other = techPackService.create(f.style, PackStage.PROD);
        techPackService.save(other, saveReq(0, f, null));
        techPackService.publish(other, 1);

        int historyBefore = count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ?", dev);
        assertThat(historyBefore).isGreaterThan(0);
        techPackService.delete(dev);
        assertThat(count("SELECT count(*) FROM tp_tech_pack WHERE id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM tp_bom_item WHERE tech_pack_id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM tp_craft_section WHERE tech_pack_id = ?", dev)).isZero();
        assertThat(count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ?", dev)).isEqualTo(historyBefore);
        assertThat(count("SELECT count(*) FROM sys_data_log WHERE table_name = 'tp_tech_pack' AND row_id = ? AND action = 'DELETE'", dev)).isEqualTo(1);
        assertThat(lock).isEqualTo(1);

        assertThatThrownBy(() -> techPackService.delete(other))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        assertThat(count("SELECT count(*) FROM tp_bom_item WHERE tech_pack_id = ?", other)).isEqualTo(1);
    }

    /** 规则（docs/api/01 §3.9）：研发 BOM 不看价格档案，取 SKU 单价；换算成元 / 库存单位 */
    @Test
    void bomDefaults_devIgnoresArchive() {
        Fixture f = fixture();
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, 99, 'METER', 13, DATE '2026-01-01')""", f.supplier, f.material);
        long dev = techPackService.create(f.style, PackStage.DEV);
        BomDefaultReq req = new BomDefaultReq();
        req.setMaterialId(f.material);
        req.setSkuIds(List.of(f.skuA));
        BomDefaultVO d = techPackService.bomDefaults(dev, List.of(req)).getFirst();
        assertThat(d.getSupplierId()).isEqualTo(f.supplier);
        assertThat(d.getRefPrice()).isEqualByComparingTo("18.5");
        assertThat(d.getPriceFrom()).isEqualTo(BomPricing.FROM_SKU);
        assertThat(d.getLossPct()).isEqualByComparingTo("3");

        long prod = techPackService.create(f.style, PackStage.PROD);
        BomDefaultVO p = techPackService.bomDefaults(prod, List.of(req)).getFirst();
        assertThat(p.getRefPrice()).isEqualByComparingTo("99");
        assertThat(p.getPriceFrom()).isEqualTo(BomPricing.FROM_ARCHIVE);
    }

    /**
     * 规则（PRD §6.9.2）：工艺标准的默认文字、默认包装方式存 sys_config，测量方法存字典 size_point 的 text_value；
     * 新建资料包时抄过去，尺寸指示的部位下拉带上新加的部位和测量方法；包装方式不是启用的字典值 → 1000
     */
    @Test
    void craftStandard_defaultsFlowIntoNewDraft() {
        Fixture f = fixture();
        CraftTextsReq texts = new CraftTextsReq();
        texts.setTexts(Map.of(CraftSection.CUTTING, "  松布 24 小时  ", CraftSection.NOTE, ""));
        texts.setPackingMode("FLAT");
        craftStandardService.saveTexts(texts);
        String point = "IT 部位" + suffix();
        craftStandardService.addPoint(point, "平量");
        assertThatThrownBy(() -> craftStandardService.addPoint(point, null))
                .isInstanceOf(BizException.class).hasMessageContaining("已经有了");

        long dev = techPackService.create(f.style, PackStage.DEV);
        TechPackDetailVO draft = techPackService.detail(dev);
        assertThat(draft.getPackingMode()).isEqualTo("FLAT");
        assertThat(draft.getCraftSections()).filteredOn(c -> c.section() == CraftSection.CUTTING)
                .singleElement().satisfies(c -> assertThat(c.content()).isEqualTo("松布 24 小时"));
        assertThat(draft.getCraftSections()).filteredOn(c -> c.section() == CraftSection.NOTE)
                .singleElement().satisfies(c -> assertThat(c.content()).isEmpty());
        assertThat(draft.getPointOptions()).contains(new PointOption(point, "平量"));

        texts.setPackingMode("NO_SUCH_MODE");
        assertThatThrownBy(() -> craftStandardService.saveTexts(texts)).isInstanceOf(BizException.class);
    }

    /** 工序单价保留数据库的四位精度；加工费合计才按金额舍入，隐藏价格的导出仍不泄露工价。 */
    @Test
    void exportPdfKeepsSubCentProcessPricesAndRoundsOnlyTotals() throws Exception {
        Fixture f = fixture();
        long id = techPackService.create(f.style, PackStage.PROD);
        TechPackSaveReq req = saveReq(0, f, null);
        ProcessForm subCent = new ProcessForm();
        subCent.setName("精细工序单价");
        subCent.setPrice(new BigDecimal("0.0050"));
        ProcessForm fourPlaces = new ProcessForm();
        fourPlaces.setName("四位小数工价");
        fourPlaces.setPrice(new BigDecimal("1.2345"));
        req.setProcesses(List.of(subCent, fourPlaces));
        techPackService.save(id, req);

        assertThat(jdbcTemplate.queryForList("SELECT price FROM tp_process WHERE tech_pack_id=? ORDER BY seq",
                BigDecimal.class, id)).containsExactly(new BigDecimal("0.0050"), new BigDecimal("1.2345"));
        TechPackPdfService.PdfFile priced = pdfService.export(id, f.colorA, true, false);
        Path evidence = Path.of(System.getProperty("pack.price.evidence", "test-artifacts/full-audit/p54/process-unit-prices.pdf"));
        Files.createDirectories(evidence.getParent());
        Files.write(evidence, priced.content());
        String text = flat(priced.content());
        assertThat(text).contains("精细工序单价", "0.005", "四位小数工价", "1.2345", "1.24");
        String unpriced = flat(pdfService.export(id, f.colorA, false, false).content());
        assertThat(unpriced).doesNotContain("精细工序单价", "0.005", "四位小数工价", "1.2345");
    }

    /**
     * 规则（PRD §6.4.6、架构 §11.1）：导出的工艺单读回文字——不含价格时没有单价、单件成本、工序工价；草稿印"草稿"；
     * 物料清单按颜色出，SKU 写成"物料名-颜色"；工艺标准的图片一张一页附在后面，不附就没有；只出一个颜色时没有别的颜色；每次导出记一条操作记录
     */
    @Test
    void exportPdf() throws Exception {
        Fixture f = fixture();
        long id = techPackService.create(f.style, PackStage.PROD);
        TechPackSaveReq req = saveReq(0, f, null);
        SpecPointForm spec = new SpecPointForm();
        spec.setPoint("后中长");
        spec.setMethod("后领中量到底边");
        spec.setToleranceCm(new BigDecimal("0.5"));
        spec.setValues(Map.of("S", new BigDecimal("64"), "M", new BigDecimal("66"), "L", new BigDecimal("68")));
        req.setSpecPoints(List.of(spec));
        ProcessForm process = new ProcessForm();
        process.setName("合肩缝");
        process.setStdSeconds(40);
        process.setPrice(new BigDecimal("1.2"));
        req.setProcesses(List.of(process));
        techPackService.save(id, req);
        String code = jdbcTemplate.queryForObject("SELECT code FROM sty_style WHERE id = ?", String.class, f.style);

        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("it-techpack");
        Files.createDirectories(dir);
        Path png = dir.resolve("std-" + suffix() + ".png");
        ImageIO.write(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        Long attachment = jdbcTemplate.queryForObject("""
                INSERT INTO biz_attachment (doc_type, doc_id, category, sort, file_url, file_name, file_size)
                VALUES ('CRAFT_STANDARD', 0, 'FILE', 0, ?, '做工标准.png', 100) RETURNING id""",
                Long.class, "/files/it-techpack/" + png.getFileName());
        try {
            TechPackPdfService.PdfFile plain = pdfService.export(id, null, false, true);
            assertThat(plain.fileName()).isEqualTo(code + "-IT 资料包款-工艺单-V1.pdf");
            String text = flat(plain.content());
            assertThat(text).contains("工艺单", "草稿", "尺寸标准指示", "后中长", "面料用量", "IT资料包面料-IT甲色", "IT资料包面料-IT乙色",
                    "缝制要求", "针距3cm12针", "附页1·做工标准");
            assertThat(text).doesNotContain("18.50", "单件成本", "合肩缝", "核价");

            TechPackPdfService.PdfFile priced = pdfService.export(id, f.colorA, true, false);
            String pricedText = flat(priced.content());
            assertThat(pricedText).contains("18.50/米", "28.58", "核价", "合肩缝", "IT资料包面料-IT甲色")
                    .doesNotContain("IT资料包面料-IT乙色", "附页");
            assertThat(count("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'TECH_PACK' AND doc_id = ? AND action = 'export-pdf'", id))
                    .isEqualTo(2);
            // 留一份在 target/ 下，改版式时打开看
            Files.write(Paths.get("target", "techpack-it-plain.pdf"), plain.content());
            Files.write(Paths.get("target", "techpack-it-priced.pdf"), priced.content());
        } finally {
            jdbcTemplate.update("DELETE FROM biz_attachment WHERE id = ?", attachment);
            Files.deleteIfExists(png);
        }
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

    /** 一行随款色的面料，统一用量 1.5、损耗 3%、单价 18.5，两个颜色各配一个 SKU；工艺指示写一段 */
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
        CraftSectionForm sewing = new CraftSectionForm();
        sewing.setSection(CraftSection.SEWING);
        sewing.setContent("针距 3cm 12 针");
        sewing.setImages(List.of());
        TechPackSaveReq req = new TechPackSaveReq();
        req.setVersionLock(versionLock);
        req.setVersionNote(note);
        req.setSpecPoints(new ArrayList<>());
        req.setBomItems(List.of(item));
        req.setCraftSections(List.of(sewing));
        req.setProcesses(new ArrayList<>());
        return req;
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

    /** PDF 里的文字，去掉所有空白（表格里折行的地方会断开） */
    private static String flat(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
        }
    }

    private static String suffix() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
    }

}
