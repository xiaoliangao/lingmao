package com.lingmao.scm.module.settle.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.settle.dto.CompareNotesReq;
import com.lingmao.scm.module.settle.dto.CompareResult;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.PendingStatementQuery;
import com.lingmao.scm.module.settle.dto.PendingStatementRow;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.dto.StatementCreatedVO;
import com.lingmao.scm.module.settle.dto.StatementDetailVO;
import com.lingmao.scm.module.settle.dto.StatementItemVO;
import com.lingmao.scm.module.settle.dto.StatementItemsReq;
import com.lingmao.scm.module.settle.entity.StatementSourceType;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import com.lingmao.scm.module.settle.service.DeductionService;
import com.lingmao.scm.module.settle.service.StatementService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 供应商对账在真库上（RFC 019 §5）：拉单据的 SQL（采购单位、退货负数、回货按生产单单价、次品扣款），
 * 部分唯一索引锁单据（同时生成只有一张成功），入库单反审核被拦（1005），比对结果存进 JSONB 再读出来，
 * 差异写了说明才能确认，作废放开单据和扣款
 */
@IntegrationTest
class SettleIT {

    private static final List<String> PERMS = List.of("settle:statement:list", "settle:statement:edit", "settle:statement:confirm",
            "settle:deduction:list", "settle:deduction:edit", "inventory:doc:list", "inventory:doc:audit", "data:all");

    @Autowired
    private StatementService statementService;
    @Autowired
    private DeductionService deductionService;
    @Autowired
    private InvDocService invDocService;
    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    /**
     * 上个月：面料厂入库 RK（两行：10 米 × 20、5 米 × 30，送货单号 SH…）、退货 2 米 × 20；加工厂回货 100 件（次品 2）× 38，扣款 100。
     * 待对账两组 → 生成两张 → 再生成报"没有要对的单据" → 入库单反审核 1005、扣款不能改 →
     * 传面料厂的 Excel：送货单那张多算 10 元（不一致）、退货一致、多一张对方有的 → 不写说明确认被拦 → 写了能确认 →
     * 加工厂那张移出扣款（扣款又能改）、导出再传回来全部一致、作废 → 回货单的引用没了、又回到待对账
     */
    @Test
    void generateCompareConfirmVoid() throws Exception {
        Fixture f = fixture();
        LocalDate from = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        LocalDate to = from.plusMonths(1).minusDays(1);
        String s = f.tag;
        long in = purchaseDoc(f, "PURCHASE_IN", "RK" + s, from.plusDays(4), "SH" + s,
                new String[][]{{"10", "20", "200"}, {"5", "30", "150"}});
        long ret = purchaseDoc(f, "PURCHASE_RETURN", "TH" + s, from.plusDays(19), null, new String[][]{{"2", "20", "40"}});
        purchaseDoc(f, "PURCHASE_IN", "RKN" + s, LocalDate.now(), null, new String[][]{{"1", "20", "20"}});   // 这个月的，不进
        long receipt = receipt(f, "HH" + s, from.plusDays(14), 100, 2, "38");
        long deduction = deductionService.create(new DeductionReq(null, f.company, f.factory, from.plusDays(24), new BigDecimal("100"),
                "延期 2 天", "JG" + s));

        List<PendingStatementRow> pending = statementService.pending(new PendingStatementQuery());
        assertThat(pending).filteredOn(p -> p.getCompanyId() == f.company && p.getPeriodStart().equals(from))
                .extracting(PendingStatementRow::getPartnerId, PendingStatementRow::getDocCount, r -> n(r.getAmount()))
                .containsExactlyInAnyOrder(tuple(f.supplier, 2, "310"), tuple(f.factory, 2, "3624"));

        // 生成：面料厂 3 行（退货负数），加工厂 2 行（回货 3800 扣 76，扣款 100）
        StatementCreatedVO sup = statementService.create(new StatementCreateReq(f.company, f.supplier, from, to, null));
        StatementCreatedVO fac = statementService.create(new StatementCreateReq(f.company, f.factory, from, to, "十月加工费"));
        StatementDetailVO d = statementService.detail(sup.id());
        assertThat(d.getItems()).extracting(StatementItemVO::getSourceType, StatementItemVO::getUnit, i -> n(i.getQty()),
                        i -> n(i.getAmount()), StatementItemVO::getDeliveryNo)
                .containsExactly(tuple(StatementSourceType.PURCHASE_IN, "METER", "10", "200", "SH" + s),
                        tuple(StatementSourceType.PURCHASE_IN, "METER", "5", "150", "SH" + s),
                        tuple(StatementSourceType.PURCHASE_RETURN, "METER", "-2", "-40", null));
        assertThat(d.getPayableAmount()).isEqualByComparingTo("310");
        assertThat(d.getActions()).contains("send", "compare", "void");
        StatementDetailVO fd = statementService.detail(fac.id());
        assertThat(fd.getItems()).extracting(StatementItemVO::getSourceType, StatementItemVO::getParentId,
                        i -> n(i.getAmount()), i -> n(i.getDeductAmount()))
                .containsExactly(tuple(StatementSourceType.PRODUCTION_RECEIPT, f.prdOrder, "3800", "76"),
                        tuple(StatementSourceType.DEDUCTION, null, "0", "100"));
        assertThat(fd.getPayableAmount()).isEqualByComparingTo("3624");
        assertThatThrownBy(() -> statementService.create(new StatementCreateReq(f.company, f.supplier, from, to, null)))
                .hasMessageContaining("没有要对的单据");

        // 锁单据：入库单反审核 1005（列出对账单）、扣款不能改
        assertThat(referenceService.referrers(BizType.INV_DOC, in)).extracting(DocRef::no).containsExactly(sup.no());
        assertThat(referenceService.referrers(BizType.PRODUCTION_RECEIPT, receipt)).extracting(DocRef::no).containsExactly(fac.no());
        int inVersion = jdbc.queryForObject("SELECT version FROM inv_doc WHERE id = ?", Integer.class, in);
        assertThatThrownBy(() -> invDocService.act(in, "unaudit", new DocActionReq(inVersion, null)))
                .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_doc WHERE id = ?", String.class, in)).isEqualTo("APPROVED");
        assertThatThrownBy(() -> deductionService.update(deduction, new DeductionReq(0, f.company, f.factory, from.plusDays(24),
                new BigDecimal("80"), "延期 2 天", null))).hasMessageContaining("已经进了对账单 " + fac.no());

        // 比对面料厂的 Excel
        byte[] theirs = sheet(new Object[][]{
                {"送货日期", "送货单号", "品名", "数量", "单价", "金额（元）"},
                {from.plusDays(4).toString(), "SH" + s, "面料", 10, 20, 200},
                {from.plusDays(4).toString(), "SH" + s, "面料", 5, 32, 160},
                {from.plusDays(19).toString(), "TH" + s, "面料 退货", 2, 20, 40},
                {from.plusDays(22).toString(), "SHX" + s, "面料", 3, 20, 60},
                {"合计", null, null, null, null, 360}});
        CompareResult c = statementService.compare(sup.id(), d.getVersion(), new MockMultipartFile("file", "对方10月.xlsx", null, theirs));
        assertThat(c.rows()).extracting(CompareResult.Row::outcome, r -> n(r.diffAmount()))
                .containsExactly(tuple(CompareResult.Outcome.DIFF, "10"), tuple(CompareResult.Outcome.MATCH, "0"),
                        tuple(CompareResult.Outcome.THEIRS_ONLY, "60"));
        d = statementService.detail(sup.id());
        assertThat(d.getTheirAmount()).isEqualByComparingTo("380");
        assertThat(d.getDiffCount()).isEqualTo(2);
        assertThat(d.getCompare().rows()).hasSize(3);
        assertThat(statementService.theirFiles(sup.id())).extracting(a -> a.name()).containsExactly("对方10月.xlsx");

        statementService.act(sup.id(), "send", new DocActionReq(d.getVersion(), null));
        int sentVersion = statementService.detail(sup.id()).getVersion();
        assertThatThrownBy(() -> statementService.act(sup.id(), "confirm", new DocActionReq(sentVersion, null)))
                .hasMessageContaining("还有 2 处差异没写说明");
        int noted = statementService.saveNotes(sup.id(), new CompareNotesReq(sentVersion, List.of(
                new CompareNotesReq.Note("1", "对方单价写错，按 30 算"), new CompareNotesReq.Note("3", "对方 11 月的货，下月对"))));
        statementService.act(sup.id(), "confirm", new DocActionReq(noted, "电话对过"));
        d = statementService.detail(sup.id());
        assertThat(d.getStatus()).isEqualTo(StatementStatus.CONFIRMED);
        assertThat(d.getConfirmNote()).isEqualTo("电话对过");
        assertThat(d.getActions()).isEmpty();

        // 加工厂：移出扣款 → 扣款又能改；导出再传回来全部一致；作废 → 回货单放开
        StatementDetailVO fd2 = statementService.detail(fac.id());
        long dedItem = fd2.getItems().get(1).getId();
        int v = statementService.removeItems(fac.id(), new StatementItemsReq(fd2.getVersion(), List.of(dedItem)));
        assertThat(deductionService.update(deduction, new DeductionReq(0, f.company, f.factory, from.plusDays(24), new BigDecimal("80"),
                "延期 2 天", null))).isEqualTo(1);
        assertThat(statementService.detail(fac.id()).getPayableAmount()).isEqualByComparingTo("3724");
        CompareResult round = statementService.compare(fac.id(), v,
                new MockMultipartFile("file", "导出.xlsx", null, statementService.export(fac.id())));
        assertThat(round.rows()).extracting(CompareResult.Row::outcome).containsOnly(CompareResult.Outcome.MATCH);
        assertThat(round.diff()).isEqualByComparingTo("0");
        statementService.act(fac.id(), "void", new DocActionReq(v + 1, "重开"));
        assertThat(referenceService.referrers(BizType.PRODUCTION_RECEIPT, receipt)).isEmpty();
        assertThat(statementService.pending(new PendingStatementQuery()))
                .filteredOn(p -> p.getCompanyId() == f.company && p.getPartnerId() == f.factory && p.getPeriodStart().equals(from))
                .extracting(PendingStatementRow::getDocCount).containsExactly(2);
        assertThat(ret).isPositive();
    }

    /** 两个人同时生成同一家同一个月的：只有一张成功，另一个报 1002（唯一索引兜底）或者"没有要对的单据" */
    @Test
    void concurrentCreate_onlyOneWins() throws Exception {
        Fixture f = fixture();
        LocalDate from = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        LocalDate to = from.plusMonths(1).minusDays(1);
        purchaseDoc(f, "PURCHASE_IN", "RKC" + f.tag, from.plusDays(3), null, new String[][]{{"1", "10", "10"}});
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<String> task = () -> {
                    TestIdentity.login(f.user, PERMS);
                    go.await();
                    try {
                        return statementService.create(new StatementCreateReq(f.company, f.supplier, from, to, null)).no();
                    } catch (BizException e) {
                        return "ERR";
                    } finally {
                        TestIdentity.clear();
                    }
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> r : results) {
                out.add(r.get());
            }
            assertThat(out).filteredOn(x -> !x.equals("ERR")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stl_statement_item i JOIN stl_statement s ON s.id = i.statement_id
                WHERE s.partner_id = ? AND i.active""", Integer.class, f.supplier)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 数据

    record Fixture(String tag, long company, long user, long supplier, long factory, long warehouse, long materialSku, long style,
                   long styleSku, long prdOrder, long prdOrderItem) {
    }

    /** 主体、用户、面料供应商、加工厂、物料仓、一种物料、一个款（一个 SKU）、一张已审核的外发加工单（单价 38） */
    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbc.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "S" + s.substring(1), "IT 对账主体" + s, "IT 对账主体" + s);
        long user = jdbc.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-stl-" + s, "IT 对账" + s, company);
        long supplier = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITSS" + s, "IT 对账面料" + s + "厂", "IT 对账面料" + s);
        long factory = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITSF" + s, "IT 对账制衣" + s + "厂", "IT 对账制衣" + s);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITSW" + s, "IT 对账仓" + s);
        long materialCategory = jdbc.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 对账物料" + s);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct)
                VALUES (?, 'IT 对账面料', ?, 'FABRIC', 'METER', 'METER', 0) RETURNING id""", Long.class, "ITSM" + s, materialCategory);
        long color = TestColors.insert(jdbc, "IT 对账色" + s);
        long materialSku = jdbc.queryForObject("INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITSM" + s + "-A", color);
        long sizeGroup = jdbc.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 对账尺码" + s);
        jdbc.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M')", sizeGroup);
        long category = jdbc.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 对账品类" + s);
        long style = jdbc.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, status)
                VALUES (?, 'IT 对账款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 'ENABLED') RETURNING id""",
                Long.class, "ITST" + s, category, sizeGroup);
        long styleSku = jdbc.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, "ITST" + s + "-A-M", color);
        long order = jdbc.queryForObject("""
                INSERT INTO ord_order (no, company_id, order_type, order_date, required_date, sales_id, merch_id, status)
                VALUES (?, ?, 'STOCK', current_date, current_date + 60, ?, ?, 'APPROVED') RETURNING id""",
                Long.class, "DDIT" + s, company, user, user);
        long orderItem = jdbc.queryForObject("""
                INSERT INTO ord_order_item (order_id, seq, style_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 100) RETURNING id""",
                Long.class, order, style, styleSku);
        long pack = jdbc.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id", Long.class, style);
        long prd = jdbc.queryForObject("""
                INSERT INTO prd_order (no, company_id, kind, factory_id, process_mode, order_id, style_id, tech_pack_id, order_date, due_date,
                                       unit_price, tax_pct, merch_id, settle_method, status)
                VALUES (?, ?, 'OUTSOURCE', ?, 'CMT', ?, ?, ?, current_date - 60, current_date + 30, 38, 3, ?, 'MONTHLY', 'RUNNING')
                RETURNING id""", Long.class, "JGIT" + s, company, factory, order, style, pack, user);
        long prdItem = jdbc.queryForObject("""
                INSERT INTO prd_order_item (prd_order_id, seq, order_item_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 100) RETURNING id""",
                Long.class, prd, orderItem, styleSku);
        TestIdentity.grant(jdbc, user, PERMS);
        TestIdentity.login(user, PERMS);
        TestIdentity.company(company);
        return new Fixture(s, company, user, supplier, factory, warehouse, materialSku, style, styleSku, prd, prdItem);
    }

    /** 已审核的采购入库（入库）、采购退货（出库）：lines 每行 {采购数量, 采购单价, 金额} */
    private long purchaseDoc(Fixture f, String type, String no, LocalDate date, String deliveryNo, String[][] lines) {
        boolean in = type.equals("PURCHASE_IN");
        long doc = jdbc.queryForObject("""
                INSERT INTO inv_doc (no, company_id, kind, doc_type, status, source_type, source_no, partner_id, doc_date,
                                     supplier_delivery_no, to_warehouse_id, from_warehouse_id, create_by)
                VALUES (?, ?, ?, ?, 'APPROVED', 'PURCHASE_ORDER', ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                no, f.company, in ? "IN" : "OUT", type, "CGIT" + f.tag, f.supplier, date, deliveryNo,
                in ? f.warehouse : null, in ? null : f.warehouse, f.user);
        for (int i = 0; i < lines.length; i++) {
            BigDecimal qty = new BigDecimal(lines[i][0]);
            jdbc.update("""
                    INSERT INTO inv_doc_item (doc_id, seq, material_sku_id, qty, purchase_unit, purchase_qty, conversion_factor,
                                              purchase_price, price, amount)
                    VALUES (?, ?, ?, ?, 'METER', ?, 1, ?, ?, ?)""", doc, i + 1, f.materialSku, qty, qty,
                    new BigDecimal(lines[i][1]), new BigDecimal(lines[i][1]), new BigDecimal(lines[i][2]));
        }
        return doc;
    }

    /** 已登记的回货单：qty 件，其中 defect 件次品 */
    private long receipt(Fixture f, String no, LocalDate date, int qty, int defect, String unused) {
        long r = jdbc.queryForObject("""
                INSERT INTO prd_receipt (no, company_id, prd_order_id, receipt_date, status) VALUES (?, ?, ?, ?, 'APPROVED') RETURNING id""",
                Long.class, no, f.company, f.prdOrder, date);
        jdbc.update("""
                INSERT INTO prd_receipt_item (receipt_id, seq, prd_order_item_id, style_sku_id, qty, defect_qty) VALUES (?, 1, ?, ?, ?, ?)""",
                r, f.prdOrderItem, f.styleSku, qty, defect);
        return r;
    }

    /** 数比较用：去掉末尾的 0、不写成 3.1E+2 */
    private static String n(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static byte[] sheet(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("对账");
            for (int r = 0; r < rows.length; r++) {
                Row row = sh.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (v != null) {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
