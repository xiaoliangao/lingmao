package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderChangeCreateReq;
import com.lingmao.scm.module.order.dto.OrderChangeDetailVO;
import com.lingmao.scm.module.order.dto.OrderChangeForm;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderDetailVO;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import com.lingmao.scm.module.order.service.OrderChangeService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 需求订单在真库上（docs/api/02）：列表的 XML（关键字一次查几个、按款筛、各状态条数）、两级审批、审过锁资料包；
 * 变更单比出改动、写回订单、一张订单只能有一张没审完的（部分唯一索引兜底）、取消订单；导入按分单号拆单、导出
 */
@IntegrationTest
class OrderIT {

    private static final List<String> PERMS = List.of("order:order:list", "order:order:add", "order:order:edit",
            "order:order:remove", "order:order:submit", "order:order:void", "order:order:audit", "order:order:import",
            "order:order:export", "order:change:list", "order:change:add", "order:change:edit", "order:change:remove",
            "order:change:submit", "order:change:void", "order:change:audit");

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderChangeService changeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SqlSessionFactory sqlSessionFactory;
    @Autowired
    private DataSource dataSource;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(long userId, List<String> perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti-it"), null, perms.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * 规则（docs/api/02 §3.2、§3.6、§3.7，架构 §4.8）：新建算合计；列表关键字一次查几个（订单号 + 小写款号）、按款筛、条数；
     * 金额超过规则门槛加一级：第 1 级审过还是待审核（记承诺交期、不锁资料包），规则上的人（不在数据范围里）也能打开、审第 2 级，
     * 审过每行锁定现在生效的生产 BOM
     */
    @Test
    void createListAndTwoLevelAudit() {
        Fixture f = fixture();
        DocSaved saved = createOrder(f, Map.of(f.sku("A", "M"), 100, f.sku("B", "L"), 50));
        long id = saved.id();

        OrderDetailVO d = orderService.detail(id);
        assertThat(d.getTotalQty()).isEqualTo(150);
        assertThat(d.getTotalAmount()).isEqualByComparingTo("13350");
        assertThat(d.getCompanyName()).isEqualTo(f.companyName);
        assertThat(d.getStyles()).singleElement().satisfies(s -> {
            assertThat(s.getStyleCode()).isEqualTo(f.styleCode);
            assertThat(s.getTechPackId()).isNull();
            assertThat(s.getCurrentPackId()).isEqualTo(f.packV1);
        });
        assertThat(d.getItems()).extracting(i -> i.getSkuCode(), i -> i.getQty())
                .containsExactly(tuple(f.styleCode + "-A-M", 100), tuple(f.styleCode + "-B-L", 50));
        assertThat(d.getActions()).contains("edit", "submit", "remove", "void");

        OrderQuery q = new OrderQuery();
        q.setKeyword(saved.no() + "、" + f.styleCode.toLowerCase());
        q.setStyleId(f.style);
        assertThat(orderService.page(q).getList()).singleElement().satisfies(r -> {
            assertThat(r.getNo()).isEqualTo(saved.no());
            assertThat(r.getStyles()).extracting(s -> s.styleCode()).containsExactly(f.styleCode);
            assertThat(r.getMerchName()).isEqualTo(f.userName);
        });
        assertThat(orderService.counts(q)).containsExactly(Map.entry("DRAFT", 1L));

        jdbcTemplate.update("INSERT INTO biz_approval_rule (biz_type, over_amount, user_id) VALUES ('ORDER', 10000, ?)", f.approver);
        try {
            orderService.act(id, "submit", new DocActionReq(0, null));
            orderService.audit(id, new OrderAuditReq(1, LocalDate.now().plusDays(40)));
            d = orderService.detail(id);
            assertThat(d.getStatus()).isEqualTo("PENDING");
            assertThat(d.getCommitDate()).isEqualTo(LocalDate.now().plusDays(40));
            assertThat(lockedPacks(id)).containsOnlyNulls();

            loginAs(f.approver, List.of("order:order:list"));
            OrderQuery approvalQuery = new OrderQuery(); approvalQuery.setKeyword(saved.no());
            assertThat(orderService.page(approvalQuery).getList()).isEmpty();
            approvalQuery.setAuditForMe(true); approvalQuery.setMine(true);
            approvalQuery.setSalesId(Long.MAX_VALUE); approvalQuery.setMerchId(Long.MAX_VALUE);
            assertThat(orderService.page(approvalQuery).getList()).extracting(r -> r.getId()).containsExactly(id);
            assertThat(orderService.counts(approvalQuery)).containsExactly(Map.entry("PENDING", 1L));
            approvalQuery.setBrand("NO-SUCH-BRAND");
            assertThat(orderService.page(approvalQuery).getList()).isEmpty();
            assertThat(orderService.detail(id).getActions()).contains("audit", "reject");
            orderService.audit(id, new OrderAuditReq(2, LocalDate.now().plusDays(45)));
        } finally {
            jdbcTemplate.update("DELETE FROM biz_approval_rule WHERE user_id = ?", f.approver);
        }
        assertThatThrownBy(() -> orderService.detail(id))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(404));

        loginAs(f.user, PERMS);
        d = orderService.detail(id);
        assertThat(d.getStatus()).isEqualTo("APPROVED");
        assertThat(d.getStyles().getFirst().getTechPackId()).isEqualTo(f.packV1);
        assertThat(d.getActions()).contains("change", "close", "unaudit").doesNotContain("schedule");
        assertThat(lockedPacks(id)).containsOnly(f.packV1);
    }

    /** 规则（docs/api/02 §3.6）：提交的检查一次列全（data 是清单）；没提交过的草稿能删，明细跟着删 */
    @Test
    void submitListsProblemsAndDraftDeletes() {
        Fixture f = fixture();
        long id = createOrder(f, Map.of(f.sku("A", "M"), 10, f.sku("C", "M"), 10, f.sku("C", "L"), 10)).id();

        assertThatThrownBy(() -> orderService.act(id, "submit", new DocActionReq(0, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getData()).isEqualTo(List.of(f.styleCode + " " + f.colorNames.get("C") + "：资料包 V1 里没给这个颜色配料"));
                });
        assertThat(orderService.detail(id).getStatus()).isEqualTo("DRAFT");

        orderService.delete(id);
        assertThat(count("SELECT count(*) FROM ord_order_item WHERE order_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM ord_order WHERE id = ?", id)).isZero();
    }

    /**
     * 规则（docs/api/02 §3.14，PRD §6.5.6）：变更单比出改了哪几处；同一张订单再提一张 1000，部分唯一索引兜底；
     * 审过写回订单（件数、新 SKU、换资料包、合计、版本号）；再提一张"取消订单"，审过订单变成已取消
     */
    @Test
    void changeWritesBackThenCancels() {
        Fixture f = fixture();
        long id = approvedOrder(f, Map.of(f.sku("A", "M"), 100, f.sku("B", "L"), 50));
        long packV2 = packV2(f);

        OrderChangeCreateReq req = changeReq(id);
        req.setQuantities(List.of(new OrderChangeForm.Qty(f.sku("A", "M"), 80), new OrderChangeForm.Qty(f.sku("B", "L"), 50),
                new OrderChangeForm.Qty(f.sku("C", "M"), 20)));
        req.setTechPacks(List.of(new OrderChangeForm.TechPack(f.style, packV2)));
        DocSaved ch = changeService.create(req);

        assertThatThrownBy(() -> changeService.create(req)).isInstanceOf(BizException.class).hasMessageContaining(ch.no() + " 没审完");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ord_change (no, company_id, order_id, change_types, reason, responsible_party, status)
                VALUES (?, ?, ?, 'QTY', '并发', 'SELF', 'DRAFT')""", "BGIT" + ThreadLocalRandom.current().nextInt(1_000_000), f.company, id))
                .isInstanceOf(DataIntegrityViolationException.class);

        OrderDetailVO d = orderService.detail(id);
        assertThat(d.getPendingChange().no()).isEqualTo(ch.no());
        assertThat(d.getActions()).doesNotContain("change", "unaudit");
        OrderChangeDetailVO cd = changeService.detail(ch.id());
        assertThat(cd.getChangeTypes()).containsExactly(ChangeType.QTY, ChangeType.COLOR_SIZE, ChangeType.TECH_PACK);
        assertThat(cd.getItems()).extracting(i -> i.getText()).containsExactly(
                f.styleCode + " 资料包：V1 → V2", f.styleCode + "-A-M：100 → 80", f.styleCode + "-C-M：新加 20 件");

        changeService.act(ch.id(), "submit", new DocActionReq(0, null));
        changeService.act(ch.id(), "audit", new DocActionReq(1, null));

        assertThat(jdbcTemplate.queryForList("SELECT style_sku_id, qty, tech_pack_id FROM ord_order_item WHERE order_id = ? ORDER BY seq", id))
                .extracting(r -> r.get("style_sku_id"), r -> r.get("qty"), r -> r.get("tech_pack_id"))
                .containsExactly(tuple(f.sku("A", "M"), 80, packV2), tuple(f.sku("B", "L"), 50, packV2), tuple(f.sku("C", "M"), 20, packV2));
        d = orderService.detail(id);
        assertThat(d.getTotalQty()).isEqualTo(150);
        assertThat(d.getTotalAmount()).isEqualByComparingTo("13350");
        assertThat(d.getPendingChange()).isNull();
        OrderChangeQuery cq = new OrderChangeQuery();
        cq.setOrderId(id);
        assertThat(changeService.page(cq).getList()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo("APPROVED");
            assertThat(r.getSummary()).contains("新加 20 件");
        });

        OrderChangeCreateReq cancel = changeReq(id);
        cancel.setCancel(true);
        DocSaved cc = changeService.create(cancel);
        changeService.act(cc.id(), "submit", new DocActionReq(0, null));
        changeService.act(cc.id(), "audit", new DocActionReq(1, null));
        assertThat(orderService.detail(id).getStatus()).isEqualTo("VOIDED");
        assertThat(changeService.counts(cq)).containsEntry("APPROVED", 2L);
    }

    /** 审核/作废的非 SKU 变更详情：不对 Map.of() 查 null，不拼 SKU 已排产提示。 */
    @ParameterizedTest
    @CsvSource({"DUE,APPROVED", "PRICE,APPROVED", "TECH_PACK,APPROVED",
            "DUE,VOIDED", "PRICE,VOIDED", "TECH_PACK,VOIDED"})
    void finalizedNonSkuChangeDetailsRemainReadable(ChangeType type, String status) {
        Fixture f = fixture();
        long orderId = approvedOrder(f, Map.of(f.sku("A", "M"), 100));
        OrderChangeCreateReq req = changeReq(orderId);
        switch (type) {
            case DUE -> req.setRequiredDate(LocalDate.now().plusDays(70));
            case PRICE -> req.setPrices(List.of(new OrderChangeForm.Price(f.style, new BigDecimal("90.1234"))));
            case TECH_PACK -> req.setTechPacks(List.of(new OrderChangeForm.TechPack(f.style, packV2(f))));
            default -> throw new IllegalArgumentException("Test covers only non-SKU changes");
        }
        long changeId = changeService.create(req).id();
        if (status.equals("APPROVED")) {
            changeService.act(changeId, "submit", new DocActionReq(0, null));
            changeService.act(changeId, "audit", new DocActionReq(1, null));
        } else {
            changeService.act(changeId, "void", new DocActionReq(0, "测试撤销"));
        }
        var detail = changeService.detail(changeId);
        assertThat(detail.getStatus()).isEqualTo(status);
        assertThat(detail.getChangeTypes()).containsExactly(type);
        assertThat(detail.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getStyleSkuId()).isNull();
            assertThat(item.getText()).isNotBlank().doesNotContain("已排");
        });
    }

    /** 规则（docs/api/02 §3.9、§3.10、§3.11）：导入按分单号一组一张草稿，有一处不对整批不导（1003）；导出一张订单一行；款式页签 */
    @Test
    void importExportAndByStyle() throws IOException {
        Fixture f = fixture();
        OrderImportHead head = new OrderImportHead();
        head.setCompanyId(f.company);
        head.setOrderType("STOCK");
        head.setRequiredDate(LocalDate.now().plusDays(30));

        byte[] bad = workbook(new String[][]{
                {"分单号", "合同号", "款号", "颜色", "尺码", "数量", "单价", "行交期"},
                {"A", null, f.styleCode, f.colorNames.get("A"), "M", "100", "89", null},
                {"A", null, "NOPE" + f.styleCode, f.colorNames.get("A"), "M", "100", "89", null}});
        assertThatThrownBy(() -> orderService.importExcel(head, new MockMultipartFile("file", "订单.xlsx", Excel.CONTENT_TYPE, bad)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID));

        byte[] good = workbook(new String[][]{
                {"分单号", "合同号", "款号", "颜色", "尺码", "数量", "单价", "行交期"},
                {"A", "HT-IT", f.styleCode, f.colorNames.get("A"), "M", "100", "89", null},
                {"A", "HT-IT", f.styleCode.toLowerCase(), f.colorCodes.get("B").toLowerCase(), "l", "50", null, null},
                {"B", null, f.styleCode, f.colorNames.get("A"), "L", "30", "89", null}});
        ImportResult result = orderService.importExcel(head, new MockMultipartFile("file", "订单.xlsx", Excel.CONTENT_TYPE, good));
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.notes()).hasSize(2);

        OrderQuery q = new OrderQuery();
        q.setStyleId(f.style);
        assertThat(orderService.page(q).getList()).extracting(r -> r.getTotalQty(), r -> r.getContractNo())
                .containsExactlyInAnyOrder(tuple(150, "HT-IT"), tuple(30, null));
        assertThat(orderService.byStyle(f.style, new PageQuery()).getList()).extracting(r -> r.getQty())
                .containsExactlyInAnyOrder(150, 30);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(orderService.export(q)))) {
            assertThat(book.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(3);
        }
    }

    @Test
    void orderCapacityRejectsJsonAndWholeExcelBatchBeforeWrites() throws IOException {
        Fixture f = fixture();
        assertThatThrownBy(() -> createOrder(f, Map.of(f.sku("A", "M"), Integer.MAX_VALUE, f.sku("B", "L"), 1)))
                .isInstanceOf(BizException.class).hasMessageContaining("总件数超出上限").extracting("code").isEqualTo(400);
        OrderImportHead head = new OrderImportHead(); head.setCompanyId(f.company); head.setOrderType("STOCK");
        head.setRequiredDate(LocalDate.now().plusDays(30));
        byte[] over = workbook(new String[][]{
                {"分单号", "合同号", "款号", "颜色", "尺码", "数量", "单价"},
                {"VALID", null, f.styleCode, f.colorNames.get("A"), "M", "1", "89"},
                {"OVER", null, f.styleCode, f.colorNames.get("A"), "M", "2147483647", "1"},
                {"OVER", null, f.styleCode, f.colorNames.get("B"), "L", "1", "1"}});
        assertThatThrownBy(() -> orderService.importExcel(head, new MockMultipartFile("file", "订单.xlsx", Excel.CONTENT_TYPE, over)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1003);
        assertThat(count("SELECT COUNT(*) FROM ord_order WHERE company_id=?", f.company)).isZero();
    }

    /** RFC010：万行列表容量门禁保留真实 Mapper 的执行计划；时延仅记录，不把测试机耗时伪装成生产 SLA。 */
    @Test
    void listCapacityExplainRecordsRealSql() throws Exception {
        Fixture f = fixture();
        String prefix = "ITCAP-" + f.company + "-";
        jdbcTemplate.update("""
                INSERT INTO ord_order (no, company_id, order_type, brand, order_date, required_date,
                                       sales_id, merch_id, total_qty, total_amount, create_by)
                SELECT ? || lpad(n::text, 6, '0'), ?, 'STOCK',
                       CASE WHEN n % 10 = 0 THEN 'ITCAP-B' ELSE 'ITCAP-A' END,
                       CURRENT_DATE - (n % 30), CURRENT_DATE + 30, ?, ?, 1, 1, ?
                FROM generate_series(1, 10000) n""", prefix, f.company, f.user, f.user, f.user);
        jdbcTemplate.update("""
                INSERT INTO ord_order_item (order_id, seq, style_id, style_sku_id, qty, price, amount)
                SELECT id, 1, ?, ?, 1, 1, 1 FROM ord_order WHERE company_id=?""", f.style, f.sku("A", "M"), f.company);
        jdbcTemplate.execute("ANALYZE ord_order");
        jdbcTemplate.execute("ANALYZE ord_order_item");

        Map<String, OrderQuery> scenarios = new LinkedHashMap<>();
        scenarios.put("unfiltered-self", new OrderQuery());
        OrderQuery single = new OrderQuery(); single.setKeyword(prefix + "000123");
        scenarios.put("single-code", single);
        OrderQuery batch = new OrderQuery();
        batch.setKeyword(IntStream.rangeClosed(1, 200).mapToObj(n -> prefix + String.format("%06d", n))
                .collect(java.util.stream.Collectors.joining(" ")));
        scenarios.put("two-hundred-codes", batch);
        OrderQuery combined = new OrderQuery(); combined.setKeyword(f.styleCode);
        combined.setBrand("ITCAP-B"); combined.setMerchId(f.user); combined.setStyleId(f.style);
        combined.setStatus(List.of(com.lingmao.scm.common.doc.DocStatus.DRAFT));
        combined.setOrderDateFrom(LocalDate.now().minusDays(7)); combined.setOrderDateTo(LocalDate.now());
        scenarios.put("combined-filters", combined);

        JsonMapper json = JsonMapper.builder().build();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("dataset", Map.of("orderHeads", 10000, "orderItems", 10000, "data", "synthetic ITCAP only"));
        evidence.put("database", jdbcTemplate.queryForObject("SHOW server_version", String.class));
        evidence.put("scope", "SELF; real OrderMapper SQL; page 1 LIMIT 20; status counts use identical filters");
        List<Object> plans = new ArrayList<>();
        for (var scenario : scenarios.entrySet()) {
            OrderQuery query = scenario.getValue();
            var actual = orderService.page(query);
            assertThat(actual.getList()).hasSizeLessThanOrEqualTo(20);
            if (scenario.getKey().equals("unfiltered-self")) assertThat(actual.getTotal()).isEqualTo(10000);
            if (scenario.getKey().equals("single-code")) assertThat(actual.getTotal()).isEqualTo(1);
            if (scenario.getKey().equals("two-hundred-codes")) assertThat(actual.getTotal()).isEqualTo(200);
            for (String method : List.of("selectRowPage", "selectCounts")) {
                var statement = sqlSessionFactory.getConfiguration().getMappedStatement(
                        "com.lingmao.scm.module.order.mapper.OrderMapper." + method);
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("q", query); parameters.put("codes", Keywords.codes(query.getKeyword()));
                parameters.put("scope", DataScope.current(false)); parameters.put("page", query.toPage());
                var bound = statement.getBoundSql(parameters);
                String sql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + bound.getSql()
                        + (method.equals("selectRowPage") ? " LIMIT 20" : "");
                try (var connection = dataSource.getConnection(); var prepared = connection.prepareStatement(sql)) {
                    new DefaultParameterHandler(statement, parameters, bound).setParameters(prepared);
                    try (var rows = prepared.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        plans.add(Map.of("scenario", scenario.getKey(), "mapper", method, "plan", json.readTree(rows.getString(1))));
                    }
                }
            }
        }
        evidence.put("plans", plans);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/audit-query-capacity.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    // ---------- 造数据 ----------

    /** 款有甲（A）、乙（B）、丙（C）三个颜色、M/L 两个尺码、6 个 SKU；生效的生产 BOM V1 只给甲、乙配了料 */
    private record Fixture(long company, String companyName, long user, String userName, long approver, long style, String styleCode,
                           Map<String, Long> skus, Map<String, Long> colors, Map<String, String> colorCodes,
                           Map<String, String> colorNames, long material, long packV1) {
        long sku(String color, String size) {
            return skus.get(color + "-" + size);
        }
    }

    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        String companyName = "IT 订单主体" + s;
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "D" + s.substring(1), companyName, companyName);
        String userName = "IT 跟单" + s;
        long user = jdbcTemplate.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "it-order-" + s, userName);
        long approver = jdbcTemplate.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "it-approver-" + s, "IT 总经理" + s);
        long approverRole = jdbcTemplate.queryForObject("INSERT INTO sys_role (code, name, data_scope) VALUES (?, ?, 'SELF') RETURNING id",
                Long.class, "it-ap-" + s, "IT 审批入口" + s);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", approver, approverRole);
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) SELECT ?, id FROM sys_menu WHERE perms='order:order:list'", approverRole);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 订单尺码" + s);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')",
                sizeGroup, sizeGroup);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 订单品类" + s);
        String styleCode = "ITOD" + s;
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status)
                VALUES (?, 'IT 订单款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED') RETURNING id""",
                Long.class, styleCode, category, sizeGroup);
        Map<String, Long> colors = new LinkedHashMap<>();
        Map<String, Long> skus = new LinkedHashMap<>();
        Map<String, String> colorCodes = new LinkedHashMap<>();
        Map<String, String> colorNames = new LinkedHashMap<>();
        String[][] colorDefs = {{"A", "IT 甲色"}, {"B", "IT 乙色"}, {"C", "IT 丙色"}};
        for (int i = 0; i < colorDefs.length; i++) {
            String code = colorDefs[i][0];
            colorNames.put(code, colorDefs[i][1] + s);
            // 短编码空间不能靠随机数保证隔离：用库内序列取候选，唯一索引原子认领。
            FixtureColor inserted = insertColor(colorNames.get(code));
            colorCodes.put(code, inserted.code());
            long color = inserted.id();
            colors.put(code, color);
            jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, ?)", style, color, i + 1);
            for (String size : List.of("M", "L")) {
                skus.put(code + "-" + size, jdbcTemplate.queryForObject(
                        "INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, ?) RETURNING id",
                        Long.class, style, styleCode + "-" + code + "-" + size, color, size));
            }
        }
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 订单物料分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                VALUES (?, 'IT 订单面料', ?, 'FABRIC', 'METER', 'METER') RETURNING id""", Long.class, "ITOD" + s, materialCategory);
        long packV1 = pack(style, 1, material, colors, List.of("A", "B"));
        loginAs(user, PERMS);
        return new Fixture(company, companyName, user, userName, approver, style, styleCode, skus, colors, colorCodes, colorNames,
                material, packV1);
    }

    /** 一版生效的生产 BOM：一行随款色的面料，给这几个颜色配了料 */
    private long pack(long style, int version, long material, Map<String, Long> colors, List<String> covered) {
        long pack = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', ?, 'EFFECTIVE') RETURNING id",
                Long.class, style, version);
        long item = jdbcTemplate.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER') RETURNING id""", Long.class, pack, material);
        for (String c : covered) {
            // 一个物料一个颜色只有一个 SKU（uk_bas_mat_sku_combo）：V2 沿用 V1 建的
            List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM bas_material_sku WHERE material_id = ? AND color_id = ?",
                    Long.class, material, colors.get(c));
            long materialSku = !existing.isEmpty() ? existing.getFirst() : jdbcTemplate.queryForObject(
                    "INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id", Long.class,
                    material, "ITODM" + pack + "-" + c, colors.get(c));
            jdbcTemplate.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)",
                    item, colors.get(c), materialSku);
        }
        return pack;
    }

    /** V1 作废、V2 生效：甲乙丙都配了料 */
    private long packV2(Fixture f) {
        jdbcTemplate.update("UPDATE tp_tech_pack SET status = 'VOIDED' WHERE id = ?", f.packV1);
        return pack(f.style, 2, f.material, f.colors, List.of("A", "B", "C"));
    }

    private DocSaved createOrder(Fixture f, Map<Long, Integer> quantities) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCompanyId(f.company);
        req.setOrderType("STOCK");
        req.setOrderDate(LocalDate.now());
        req.setRequiredDate(LocalDate.now().plusDays(30));
        req.setSalesId(f.user);
        req.setMerchId(f.user);
        req.setCurrency("CNY");
        List<OrderItemReq> items = new ArrayList<>();
        quantities.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            OrderItemReq i = new OrderItemReq();
            i.setStyleSkuId(e.getKey());
            i.setQty(e.getValue());
            i.setPrice(new BigDecimal("89"));
            items.add(i);
        });
        req.setItems(items);
        return orderService.create(req);
    }

    /** 单级审批（没有规则）走完：草稿 → 已审核 */
    private long approvedOrder(Fixture f, Map<Long, Integer> quantities) {
        long id = createOrder(f, quantities).id();
        orderService.act(id, "submit", new DocActionReq(0, null));
        orderService.audit(id, new OrderAuditReq(1, LocalDate.now().plusDays(30)));
        return id;
    }

    private static OrderChangeCreateReq changeReq(long orderId) {
        OrderChangeCreateReq req = new OrderChangeCreateReq();
        req.setOrderId(orderId);
        req.setReason("客户追加");
        req.setResponsibleParty(ResponsibleParty.SELF);
        return req;
    }

    private List<Long> lockedPacks(long orderId) {
        return jdbcTemplate.queryForList("SELECT tech_pack_id FROM ord_order_item WHERE order_id = ? ORDER BY seq", Long.class, orderId);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private record FixtureColor(long id, String code) {}

    private FixtureColor insertColor(String name) {
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int attempt = 0; attempt < 36 * 36 * 36; attempt++) {
            long sequence = jdbcTemplate.queryForObject(
                    "SELECT nextval(pg_get_serial_sequence('bas_color', 'id'))", Long.class);
            int value = (int) (sequence % (36 * 36 * 36));
            String code = "" + digits.charAt(value / (36 * 36))
                    + digits.charAt(value / 36 % 36) + digits.charAt(value % 36);
            List<Long> ids = jdbcTemplate.queryForList("""
                    INSERT INTO bas_color (id, code, name) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)
                    ON CONFLICT (code) DO NOTHING RETURNING id""", Long.class, sequence, code, name);
            if (!ids.isEmpty()) return new FixtureColor(ids.getFirst(), code);
        }
        throw new IllegalStateException("No unused three-character color code for test fixture");
    }

    /** 一张"数据"表，全是文字格子；null 的格子不建 */
    private static byte[] workbook(String[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    if (rows[i][j] != null) {
                        row.createCell(j).setCellValue(rows[i][j]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
