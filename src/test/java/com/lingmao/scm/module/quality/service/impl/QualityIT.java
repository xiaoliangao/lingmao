package com.lingmao.scm.module.quality.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.quality.dto.QcCreatedVO;
import com.lingmao.scm.module.quality.dto.QcPendingQuery;
import com.lingmao.scm.module.quality.dto.QcPendingRow;
import com.lingmao.scm.module.quality.dto.QcQuery;
import com.lingmao.scm.module.quality.dto.QcReq;
import com.lingmao.scm.module.quality.dto.QcReworkReq;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.dto.QcStatQuery;
import com.lingmao.scm.module.quality.dto.QcSupplierStat;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import com.lingmao.scm.module.quality.service.QcInspectionService;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.service.DeductionService;
import com.lingmao.scm.module.settle.service.StatementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 质检在真库上（RFC 019 §6）：待检的 SQL（最近的采购入库、回货，返修完成的再列出来）、部分唯一索引（来料只验一次）、
 * 回货不合格 → 返修 → 返修完成 → 第 2 次验、质量扣款和扣款登记、对账单的联动，问题类型数组读写、品类线
 */
@IntegrationTest
class QualityIT {

    private static final List<String> PERMS = List.of("quality:inspection:list", "quality:inspection:edit",
            "settle:deduction:list", "settle:deduction:edit", "settle:statement:list", "settle:statement:edit", "data:all");

    @Autowired
    private QcInspectionService qcService;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private StatementService statementService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    /**
     * 前天入库一张面料、回货 200 件 → 都在待检 → 来料让步接收扣 50，再验一次 1000 →
     * 回货第 1 次不合格扣 150（扣款登记上是"质检登记的"，那边不能改）→ 安排返修 → 返修中不能再验 → 返修完成又在待检（第 2 次）→
     * 第 2 次合格 → 不在待检了；供应商质量按家统计、问题类型数一遍 →
     * 生成面料厂的对账单把扣款拉进去 → 质检改扣款金额、删记录都报 1000，只改问题可以（扣款原因不动）
     */
    @Test
    void inspectReworkAndDeduct() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        long doc = purchaseIn(f, "RK" + f.tag, today.minusDays(2));
        long receipt = receipt(f, "HH" + f.tag, today.minusDays(2), 200);
        purchaseIn(f, "RKOLD" + f.tag, today.minusDays(45));   // 太早的不列

        QcPendingQuery pq = new QcPendingQuery();
        pq.setKeyword(f.tag);
        pq.setSize(50);
        assertThat(qcService.pending(pq).getList()).extracting(QcPendingRow::getKind, QcPendingRow::getSourceNo,
                        r -> n(r.getLotQty()), QcPendingRow::getUnit, QcPendingRow::getAttemptNo)
                .containsExactlyInAnyOrder(tuple(QcKind.INCOMING, "RK" + f.tag, "15", "METER", 1),
                        tuple(QcKind.FINAL, "HH" + f.tag, "200", "PIECE", 1));

        // 来料：让步接收扣 50；同一张再验一次 1000
        QcSourceVO src = qcService.source(QcKind.INCOMING, doc);
        assertThat(src.getAqlSample()).isEqualByComparingTo("3");   // 批量 15 → 抽 3、允收 0
        QcCreatedVO incoming = qcService.create(new QcReq(null, QcKind.INCOMING, doc, today.minusDays(1), new BigDecimal("3"),
                BigDecimal.ONE, QcResult.CONCESSION, List.of("COLOR", "FABRIC"), "卡其色轻微色差", null, new BigDecimal("50")));
        assertThatThrownBy(() -> qcService.create(new QcReq(null, QcKind.INCOMING, doc, today, BigDecimal.ONE, null,
                QcResult.PASS, null, null, null, null))).hasMessageContaining("已经验过了（" + incoming.no() + "）");

        // 回货第 1 次不合格，扣 150
        QcCreatedVO first = qcService.create(new QcReq(null, QcKind.FINAL, receipt, today.minusDays(1), new BigDecimal("32"),
                new BigDecimal("4"), QcResult.FAIL, List.of("WORKMANSHIP"), "领口不平 4 件", null, new BigDecimal("150")));
        QcRowVO row = qcService.detail(first.id());
        assertThat(row.getAttemptNo()).isEqualTo(1);
        assertThat(row.getAcceptQty()).isEqualByComparingTo("2");
        assertThat(row.getDefectTypes()).containsExactly("WORKMANSHIP");
        assertThat(row.getDeductAmount()).isEqualByComparingTo("150");
        DeductionQuery dq = new DeductionQuery();
        dq.setPartnerId(f.factory);
        DeductionRowVO ded = deductionService.page(dq).getList().getFirst();
        assertThat(ded.getSourceType()).isEqualTo("QC_INSPECTION");
        assertThat(ded.getRefNo()).isEqualTo(first.no());
        assertThat(ded.getReason()).isEqualTo("质检 " + first.no() + "：尾期验货不合格，领口不平 4 件");
        assertThatThrownBy(() -> deductionService.update(ded.getId(), new DeductionReq(ded.getVersion(), f.company, f.factory,
                today, BigDecimal.TEN, "改", null))).hasMessageContaining("是质检记录 " + first.no() + " 登记的");

        // 返修 → 返修中不能再验 → 返修完成，又在待检、第 2 次
        assertThat(qcService.reworks(f.company)).extracting(QcRowVO::getNo).containsExactly(first.no());
        int v = qcService.rework(first.id(), new QcReworkReq(row.getVersion(), "领口拆了重上，工厂 3 天后送回"));
        assertThatThrownBy(() -> qcService.source(QcKind.FINAL, receipt)).hasMessageContaining("在返修");
        assertThatThrownBy(() -> qcService.delete(first.id())).hasMessageContaining("已经安排了返修，不能删");
        qcService.reworkDone(first.id(), new QcReworkReq(v, null));
        assertThat(qcService.reworks(f.company)).isEmpty();
        assertThat(qcService.pending(pq).getList()).extracting(QcPendingRow::getSourceNo, QcPendingRow::getAttemptNo)
                .containsExactly(tuple("HH" + f.tag, 2));
        QcCreatedVO second = qcService.create(new QcReq(null, QcKind.FINAL, receipt, today, new BigDecimal("32"), BigDecimal.ONE,
                QcResult.PASS, null, null, null, null));
        assertThat(qcService.detail(second.id()).getAttemptNo()).isEqualTo(2);
        assertThat(qcService.pending(pq).getList()).isEmpty();

        // 列表、供应商质量
        QcQuery q = new QcQuery();
        q.setKeyword(f.tag);
        assertThat(qcService.page(q).getList()).extracting(QcRowVO::getNo).containsExactly(second.no(), first.no(), incoming.no());
        QcStatQuery sq = new QcStatQuery();
        sq.setCompanyId(f.company);
        List<QcSupplierStat> stats = qcService.supplierStats(sq);
        assertThat(stats).extracting(QcSupplierStat::getPartnerId, QcSupplierStat::getInspectCount, QcSupplierStat::getFailCount,
                        QcSupplierStat::getConcessionCount, s -> n(s.getDeductAmount()), QcSupplierStat::getTopDefects)
                .containsExactlyInAnyOrder(tuple(f.factory, 2L, 1L, 0L, "150", List.of("WORKMANSHIP")),
                        tuple(f.supplier, 1L, 0L, 1L, "50", List.of("COLOR", "FABRIC")));

        // 对账单拉进了来料的扣款：金额不能改、记录不能删；只改问题可以，扣款原因不动
        statementService.create(new StatementCreateReq(f.company, f.supplier, today.minusDays(10), today, null));
        QcRowVO in = qcService.detail(incoming.id());
        assertThat(in.getDeductStatementNo()).startsWith("DZ");
        assertThatThrownBy(() -> qcService.update(incoming.id(), new QcReq(in.getVersion(), null, null, in.getInspectDate(),
                new BigDecimal("3"), BigDecimal.ONE, QcResult.CONCESSION, List.of("COLOR"), "卡其色轻微色差", null, new BigDecimal("80"))))
                .hasMessageContaining("已经进了对账单 " + in.getDeductStatementNo());
        assertThatThrownBy(() -> qcService.delete(incoming.id())).hasMessageContaining("已经进了对账单");
        qcService.update(incoming.id(), new QcReq(in.getVersion(), null, null, in.getInspectDate(), new BigDecimal("3"), BigDecimal.ONE,
                QcResult.CONCESSION, List.of("COLOR"), "卡其色轻微色差，客户同意接收", null, new BigDecimal("50")));
        Map<String, Object> d = jdbc.queryForMap("SELECT amount, reason FROM stl_deduction WHERE id = ?", in.getDeductionId());
        assertThat((BigDecimal) d.get("amount")).isEqualByComparingTo("50");
        assertThat(d.get("reason")).isEqualTo("质检 " + incoming.no() + "：来料检验让步接收，卡其色轻微色差");
        assertThat(qcService.detail(incoming.id()).getDefectTypes()).containsExactly("COLOR");
    }

    /** 品类线：只看鞋类的人看不到服装生产单的回货验货，来料检验（不分线）照样看得到；记服装回货的验货 404 */
    @Test
    void lineScope_hidesOtherLines() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        long doc = purchaseIn(f, "RK" + f.tag, today.minusDays(1));
        long receipt = receipt(f, "HH" + f.tag, today.minusDays(1), 50);
        qcService.create(new QcReq(null, QcKind.INCOMING, doc, today, BigDecimal.ONE, null, QcResult.PASS, null, null, null, null));
        qcService.create(new QcReq(null, QcKind.MIDDLE, f.prdOrder, today, new BigDecimal("8"), null, QcResult.PASS, null, null, null, null));

        TestIdentity.login(f.user, TestIdentity.withLines(List.of("quality:inspection:list", "quality:inspection:edit", "line:FOOTWEAR")));
        QcQuery q = new QcQuery();
        q.setKeyword(f.tag);
        assertThat(qcService.page(q).getList()).extracting(QcRowVO::getKind).containsExactly(QcKind.INCOMING);
        QcPendingQuery pq = new QcPendingQuery();
        pq.setKeyword(f.tag);
        assertThat(qcService.pending(pq).getList()).isEmpty();
        assertThatThrownBy(() -> qcService.source(QcKind.FINAL, receipt)).isInstanceOf(BizException.class).hasMessage("回货单不存在");
    }

    record Fixture(String tag, long company, long user, long supplier, long factory, long warehouse, long materialSku,
                   long styleSku, long prdOrder, long prdOrderItem) {
    }

    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbc.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "Q" + s.substring(1), "IT 质检主体" + s, "IT 质检主体" + s);
        long user = jdbc.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-qc-" + s, "IT 质检" + s, company);
        long supplier = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITQS" + s, "IT 质检面料" + s + "厂", "IT 质检面料" + s);
        long factory = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITQF" + s, "IT 质检制衣" + s + "厂", "IT 质检制衣" + s);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITQW" + s, "IT 质检仓" + s);
        long materialCategory = jdbc.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 质检物料" + s);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct)
                VALUES (?, ?, ?, 'FABRIC', 'METER', 'METER', 0) RETURNING id""", Long.class, "ITQM" + s, "IT 质检卡其布" + s, materialCategory);
        long color = TestColors.insert(jdbc, "IT 质检色" + s);
        long materialSku = jdbc.queryForObject("INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITQM" + s + "-A", color);
        long sizeGroup = jdbc.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 质检尺码" + s);
        jdbc.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M')", sizeGroup);
        long category = jdbc.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 质检品类" + s);
        long style = jdbc.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, status)
                VALUES (?, 'IT 质检款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 'ENABLED') RETURNING id""",
                Long.class, "ITQT" + s, category, sizeGroup);
        long styleSku = jdbc.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, "ITQT" + s + "-A-M", color);
        long order = jdbc.queryForObject("""
                INSERT INTO ord_order (no, company_id, order_type, order_date, required_date, sales_id, merch_id, status)
                VALUES (?, ?, 'STOCK', current_date, current_date + 60, ?, ?, 'APPROVED') RETURNING id""",
                Long.class, "DDQ" + s, company, user, user);
        long orderItem = jdbc.queryForObject("""
                INSERT INTO ord_order_item (order_id, seq, style_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 200) RETURNING id""",
                Long.class, order, style, styleSku);
        long pack = jdbc.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id", Long.class, style);
        long prd = jdbc.queryForObject("""
                INSERT INTO prd_order (no, company_id, kind, factory_id, process_mode, order_id, style_id, tech_pack_id, order_date, due_date,
                                       unit_price, tax_pct, merch_id, settle_method, status, total_qty)
                VALUES (?, ?, 'OUTSOURCE', ?, 'CMT', ?, ?, ?, current_date - 30, current_date + 30, 38, 3, ?, 'MONTHLY', 'RUNNING', 200)
                RETURNING id""", Long.class, "JGQ" + s, company, factory, order, style, pack, user);
        long prdItem = jdbc.queryForObject("""
                INSERT INTO prd_order_item (prd_order_id, seq, order_item_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 200) RETURNING id""",
                Long.class, prd, orderItem, styleSku);
        TestIdentity.grant(jdbc, user, PERMS);
        TestIdentity.login(user, PERMS);
        TestIdentity.company(company);
        return new Fixture(s, company, user, supplier, factory, warehouse, materialSku, styleSku, prd, prdItem);
    }

    /** 已审核的采购入库：两行 10 米 + 5 米，按采购单价 20 */
    private long purchaseIn(Fixture f, String no, LocalDate date) {
        long doc = jdbc.queryForObject("""
                INSERT INTO inv_doc (no, company_id, kind, doc_type, status, source_type, source_no, partner_id, doc_date, to_warehouse_id, create_by)
                VALUES (?, ?, 'IN', 'PURCHASE_IN', 'APPROVED', 'PURCHASE_ORDER', ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                no, f.company, "CGQ" + f.tag, f.supplier, date, f.warehouse, f.user);
        int seq = 1;
        for (String qty : List.of("10", "5")) {
            BigDecimal q = new BigDecimal(qty);
            jdbc.update("""
                    INSERT INTO inv_doc_item (doc_id, seq, material_sku_id, qty, purchase_unit, purchase_qty, conversion_factor,
                                              purchase_price, price, amount)
                    VALUES (?, ?, ?, ?, 'METER', ?, 1, 20, 20, ?)""", doc, seq++, f.materialSku, q, q, q.multiply(BigDecimal.valueOf(20)));
        }
        return doc;
    }

    /** 已登记的回货单 */
    private long receipt(Fixture f, String no, LocalDate date, int qty) {
        long r = jdbc.queryForObject("""
                INSERT INTO prd_receipt (no, company_id, prd_order_id, receipt_date, status) VALUES (?, ?, ?, ?, 'APPROVED') RETURNING id""",
                Long.class, no, f.company, f.prdOrder, date);
        jdbc.update("""
                INSERT INTO prd_receipt_item (receipt_id, seq, prd_order_item_id, style_sku_id, qty, defect_qty) VALUES (?, 1, ?, ?, ?, 0)""",
                r, f.prdOrderItem, f.styleSku, qty);
        return r;
    }

    private static String n(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
