package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.dto.IssueCloseReq;
import com.lingmao.scm.module.production.dto.IssueReq;
import com.lingmao.scm.module.production.dto.NodeReportReq;
import com.lingmao.scm.module.production.dto.NodeReportResult;
import com.lingmao.scm.module.production.dto.ProductionDetailVO;
import com.lingmao.scm.module.production.dto.ProductionNodeVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.dto.SchedulePrefillVO;
import com.lingmao.scm.module.production.dto.ScheduleQuoteVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.entity.NodeReportAction;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.production.service.ProductionNodeService;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.production.service.ScheduleService;
import com.lingmao.scm.module.production.dto.NodePlanReq;
import com.lingmao.scm.module.purchase.service.NeedDateSource;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产在真库上（docs/api/05）：列表、风险、看板的 SQL，排单占订单行、回货回写订单状态、领料过账回写领料状态，
 * 锁的顺序（订单 → 生产单）都要真库才测得出
 */
@IntegrationTest
class ProductionIT {

    private static final List<String> PERMS = List.of("order:order:list", "order:order:add", "order:order:submit",
            "order:order:audit", "production:order:list", "production:order:add", "production:order:edit", "production:order:remove",
            "production:order:submit", "production:order:audit", "production:order:close", "production:order:void",
            "production:node:edit", "production:issue:edit", "production:receipt:add", "production:receipt:void",
            "production:board:list", "production:mobile:list", "inventory:doc:add", "inventory:doc:audit");

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ProductionOrderService productionOrderService;
    @Autowired
    private ProductionNodeService nodeService;
    @Autowired
    private ProductionIssueService issueService;
    @Autowired
    private ProductionReceiptService receiptService;
    @Autowired
    private ProductionBoardService boardService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private InvDocService invDocService;
    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private NeedDateSource needDateSource;
    @Autowired
    private FileUrlSigner signer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 一张订单（M 60、L 40，面料 1.5 米 / 件）排成一张外发加工单，走完：超排 1004 → 审核生成节点、记价格档案、订单生产中 →
     * 要料日期取裁剪 → 报进度变生产中、报过就不能反审核 → 领料（150 米）领料状态 DONE、退多了 1004 → 上报异常改预计交期 →
     * 回货 99 件已完成、订单已完成，超回 1004 → 作废回货单退回生产中 → 列表、看板、手机
     */
    @Test
    void scheduleAuditReportIssueReceiveVoid() {
        Fixture f = fixture();
        LocalDate commit = LocalDate.now().plusDays(60);
        long orderId = approvedOrder(f, commit);

        // 排单对话框：一个款两个 SKU，默认交期 = 承诺交期 − 10；选工厂带出加工方式（没有档案价）
        SchedulePrefillVO pre = scheduleService.prefill(orderId);
        assertThat(pre.defaultDueDate()).isEqualTo(commit.minusDays(10));
        assertThat(pre.styles()).singleElement().satisfies(s -> assertThat(s.skus()).hasSize(2));
        ScheduleQuoteVO quote = scheduleService.quote(orderId, f.style, f.factory);
        assertThat(quote.usable()).isTrue();
        assertThat(quote.prices()).extracting(ScheduleQuoteVO.ModePrice::processMode).contains("CMT", "HALF", "FULL");

        // 超排：M 订单 60，最多 ⌊61.8⌋ = 61
        long lineM = orderLine(orderId, f.skuM);
        long lineL = orderLine(orderId, f.skuL);
        assertThatThrownBy(() -> scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 62, lineL, 40))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));
        List<DocRef> made = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 60, lineL, 40)));
        assertThat(made).singleElement().satisfies(r -> assertThat(r.no()).startsWith("JG"));
        long prdId = made.getFirst().id();
        assertThat(jdbcTemplate.queryForObject("SELECT sum(scheduled_qty) FROM ord_order_item WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(100);

        ProductionDetailVO d = productionOrderService.detail(prdId);
        assertThat(d.getStatus()).isEqualTo("DRAFT");
        assertThat(d.getTotalQty()).isEqualTo(100);
        assertThat(d.getTotalAmount()).isEqualByComparingTo("3800");
        assertThat(d.getIssueStatus()).isEqualTo("NONE");

        // 审核：按字典生成节点，最后一个的计划完成 = 交期；没有档案价，记一条价格档案；订单变生产中
        productionOrderService.act(prdId, "submit", new DocActionReq(d.getVersion(), null));
        productionOrderService.act(prdId, "audit", new DocActionReq(productionOrderService.detail(prdId).getVersion(), null));
        d = productionOrderService.detail(prdId);
        assertThat(d.getStatus()).isEqualTo("APPROVED");
        assertThat(d.getNodes()).extracting(ProductionNodeVO::getNodeCode).startsWith("MATERIAL", "CUT", "SEW");
        assertThat(d.getNodes().getLast().getPlanFinish()).isEqualTo(commit.minusDays(10));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM prc_price WHERE source_type = 'PRODUCTION_ORDER' AND source_id = ?"
                + " AND type = 'PROCESS'", Integer.class, prdId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("RUNNING");
        ProductionNodeVO cut = d.getNodes().get(1);
        assertThat(needDateSource.cutStartOf(List.of(orderId))).containsEntry(orderId, cut.getPlanStart());

        // 报进度：裁剪报开始 → 生产中；车缝先报完成，提示前面没报完；报过就不能反审核
        NodeReportResult r1 = nodeService.report(prdId, cut.getId(), nodeReport(NodeReportAction.START, LocalDate.now(), null));
        assertThat(r1.status()).isEqualTo("RUNNING");
        NodeReportResult r2 = nodeService.report(prdId, d.getNodes().get(2).getId(), nodeReport(NodeReportAction.FINISH, null, 50));
        assertThat(r2.warning()).isEqualTo("前面还有节点没报完成");
        assertThat(nodeService.reports(prdId, cut.getId())).hasSize(1);
        int v = productionOrderService.detail(prdId).getVersion();
        assertThatThrownBy(() -> productionOrderService.act(prdId, "unaudit", new DocActionReq(v, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("已经报过进度"));

        // 领料：应领 100 × 1.5 = 150 米，领齐 DONE；退 200 超过已领 1004；退 10 → PARTIAL
        jdbcTemplate.update("INSERT INTO inv_stock (warehouse_id, material_sku_id, qty) VALUES (?, ?, 500)", f.warehouse, f.sku);
        assertThat(productionOrderService.materials(prdId).rows()).singleElement()
                .satisfies(m -> assertThat(m.getNeedQty()).isEqualByComparingTo("150"));
        invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId, stockReq(InvDocType.ISSUE_OUT, f, "150"));
        assertThat(productionOrderService.detail(prdId).getIssueStatus()).isEqualTo("DONE");
        assertThatThrownBy(() -> invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId, stockReq(InvDocType.RETURN_IN, f, "200")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));
        invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId, stockReq(InvDocType.RETURN_IN, f, "10"));
        assertThat(productionOrderService.detail(prdId).getIssueStatus()).isEqualTo("PARTIAL");
        assertThat(productionOrderService.materials(prdId).rows().getFirst().getIssuedQty()).isEqualByComparingTo("140");

        // 异常：影响交期的改预计交期；开始处理、关闭
        LocalDate later = commit.minusDays(5);
        long issueId = issueService.report(prdId, issueReq(later)).id();
        assertThat(productionOrderService.detail(prdId).getExpectDate()).isEqualTo(later);
        issueService.start(prdId, issueId);
        assertThatThrownBy(() -> issueService.start(prdId, issueId))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        IssueCloseReq close = new IssueCloseReq();
        close.setMeasure("补料到了，加班赶");
        issueService.close(prdId, issueId, close);
        assertThat(issueService.list(prdId)).singleElement().satisfies(i -> {
            assertThat(i.getStatus()).isEqualTo("CLOSED");
            assertThat(i.getActions()).isEmpty();
        });

        // 回货：M 60、L 39（次品 2）= 99 ≥ 97 → 已完成，订单已完成；再回 M 2 超过 61 → 1004
        long itemM = prdItem(prdId, lineM);
        long itemL = prdItem(prdId, lineL);
        var receipt = receiptService.receive(prdId, receiptReq(Map.of(itemM, 60, itemL, 39), itemL, 2));
        assertThat(receipt.status()).isEqualTo("DONE");
        assertThat(orderStatus(orderId)).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("SELECT sum(returned_qty) FROM ord_order_item WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(99);
        assertThatThrownBy(() -> receiptService.receive(prdId, receiptReq(Map.of(itemM, 2), itemM, 0)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));

        // 下游：领料、退料单，回货单，异常
        assertThat(referenceService.referrers(BizType.PRODUCTION_ORDER, prdId)).extracting(DocRef::type)
                .contains(BizType.PRODUCTION_RECEIPT, BizType.PRODUCTION_ISSUE);
        assertThat(referenceService.referrers(BizType.ORDER, orderId)).extracting(DocRef::id).contains(prdId);

        // 作废回货单：已回减回去，生产单回到生产中，订单退回生产中
        receiptService.voidReceipt(prdId, receipt.id(), "数点错了");
        d = productionOrderService.detail(prdId);
        assertThat(d.getStatus()).isEqualTo("RUNNING");
        assertThat(d.getReturnedQty()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("RUNNING");
        assertThat(receiptService.list(prdId)).singleElement().satisfies(x -> {
            assertThat(x.getStatus()).isEqualTo("VOIDED");
            assertThat(x.getVoidReason()).isEqualTo("数点错了");
        });

        // 列表（XML）、页签数、看板、手机
        ProductionQuery q = new ProductionQuery();
        q.setKind("OUTSOURCE");
        q.setKeyword(d.getNo());
        assertThat(productionOrderService.page(q).getList()).extracting(ProductionRowVO::getId).containsExactly(prdId);
        assertThat(productionOrderService.counts(q)).containsEntry("RUNNING", 1L);
        BoardQuery b = new BoardQuery();
        b.setFactoryId(f.factory);
        assertThat(boardService.cards(b).runningCount()).isEqualTo(1);
        assertThat(boardService.factories(new BoardQuery())).anySatisfy(row -> {
            assertThat(row.getFactoryId()).isEqualTo(f.factory);
            assertThat(row.getWipQty()).isEqualTo(100);
        });
        assertThat(productionOrderService.myFollow()).extracting(row -> row.id()).contains(prdId);
    }

    /**
     * docs/api/05 §3.4.1 / Q12：BUY 不必然免领料；本例在订单锁版前准备全部工厂自备料的 BOM，
     * 包工包料排单 → 审核 → 无领料回货 → 作废回货，累计退回且仍保留排单占用。
     */
    @Test
    void buyWithOnlyFactoryMaterials_scheduleAuditReceiveAndVoid_withoutIssuingMaterials() {
        Fixture f = fixture();
        assertThat(jdbcTemplate.update("""
                UPDATE tp_bom_item SET source='FACTORY'
                WHERE tech_pack_id IN (SELECT id FROM tp_tech_pack WHERE style_id=?)""", f.style)).isEqualTo(1);
        LocalDate commit = LocalDate.now().plusDays(60);
        long orderId = approvedOrder(f, commit);
        long lineM = orderLine(orderId, f.skuM);
        long lineL = orderLine(orderId, f.skuL);
        ScheduleReq req = scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 60, lineL, 40));
        req.getBlocks().getFirst().setProcessMode("FULL");
        DocRef created = scheduleService.schedule(req).getFirst();
        long prdId = created.id();
        assertThat(created.no()).startsWith("CP");
        ProductionDetailVO d = productionOrderService.detail(prdId);
        assertThat(d.getKind()).isEqualTo("BUY");
        assertThat(d.getProcessMode()).isEqualTo("FULL");
        assertThat(d.getStatus()).isEqualTo("DRAFT");
        assertThat(scheduled(orderId)).isEqualTo(100);

        productionOrderService.act(prdId, "submit", new DocActionReq(d.getVersion(), null));
        productionOrderService.act(prdId, "audit", new DocActionReq(productionOrderService.detail(prdId).getVersion(), null));
        d = productionOrderService.detail(prdId);
        assertThat(d.getStatus()).isEqualTo("APPROVED");
        assertThat(d.getNodes()).isNotEmpty();
        assertThat(d.getIssueStatus()).isNull();
        assertThat(d.getActions()).doesNotContain("issue", "returnMaterial");
        assertThat(productionOrderService.materials(prdId).rows()).isEmpty();
        assertThatThrownBy(() -> invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId,
                stockReq(InvDocType.ISSUE_OUT, f, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("没有我方采购的料");
                });
        assertThat(productionOrderService.materials(prdId).docs()).isEmpty();

        long itemM = prdItem(prdId, lineM);
        long itemL = prdItem(prdId, lineL);
        var receipt = receiptService.receive(prdId, receiptReq(Map.of(itemM, 60, itemL, 40), itemL, 2));
        assertThat(receipt.status()).isEqualTo("DONE");
        d = productionOrderService.detail(prdId);
        assertThat(d.getReturnedQty()).isEqualTo(100);
        assertThat(d.getDefectQty()).isEqualTo(2);
        assertThat(orderStatus(orderId)).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("SELECT sum(returned_qty) FROM ord_order_item WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(100);

        receiptService.voidReceipt(prdId, receipt.id(), "回货登记有误");
        d = productionOrderService.detail(prdId);
        assertThat(d.getStatus()).isEqualTo("APPROVED");
        assertThat(d.getReturnedQty()).isZero();
        assertThat(d.getDefectQty()).isZero();
        assertThat(d.getItems()).allSatisfy(i -> {
            assertThat(i.getReturnedQty()).isZero();
            assertThat(i.getDefectQty()).isZero();
        });
        assertThat(orderStatus(orderId)).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("SELECT sum(returned_qty) FROM ord_order_item WHERE order_id=?",
                Integer.class, orderId)).isZero();
        assertThat(scheduled(orderId)).isEqualTo(100);
        assertThat(receiptService.list(prdId)).singleElement().satisfies(r -> assertThat(r.getStatus()).isEqualTo("VOIDED"));
        assertThat(productionOrderService.materials(prdId).docs()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inv_stock_log WHERE material_sku_id=?",
                Integer.class, f.sku)).isZero();
    }

    /** 删草稿：件数退回订单的待排单，订单记一条；作废同样退回（docs/api/05 §3.2.5、§3.2.6） */
    @Test
    void deleteAndVoid_refundScheduled() {
        Fixture f = fixture();
        LocalDate commit = LocalDate.now().plusDays(40);
        long orderId = approvedOrder(f, commit);
        long lineM = orderLine(orderId, f.skuM);
        long lineL = orderLine(orderId, f.skuL);

        long a = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 30))).getFirst().id();
        long b = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 30, lineL, 40))).getFirst().id();
        assertThat(scheduled(orderId)).isEqualTo(100);

        productionOrderService.delete(a);
        assertThat(scheduled(orderId)).isEqualTo(70);
        productionOrderService.act(b, "submit", new DocActionReq(productionOrderService.detail(b).getVersion(), null));
        productionOrderService.act(b, "void", new DocActionReq(productionOrderService.detail(b).getVersion(), "工厂接不了"));
        assertThat(scheduled(orderId)).isZero();
        assertThat(productionOrderService.detail(b).getStatus()).isEqualTo("VOIDED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type = 'ORDER' AND doc_id = ? AND action = 'schedule'",
                Integer.class, orderId)).isEqualTo(4);
    }

    @Test
    void nodePlanRevisionBlocksSecondDevice_butReportsDoNotChangePlanRevision() {
        Fixture f = fixture();
        long id = approvedProduction(f);
        ProductionDetailVO d = productionOrderService.detail(id);
        NodePlanReq req = new NodePlanReq(); req.setPlanRevision(d.getPlanRevision());
        req.setNodes(d.getNodes().stream().map(n -> {
            NodePlanReq.Item i = new NodePlanReq.Item(); i.setNodeId(n.getId());
            i.setPlanStart(n.getPlanStart().plusDays(1)); i.setPlanFinish(n.getPlanFinish().plusDays(1)); return i;
        }).toList());
        assertThat(nodeService.updatePlan(id, req)).isEqualTo(1);
        assertThatThrownBy(() -> nodeService.updatePlan(id, req)).isInstanceOf(BizException.class).extracting("code").isEqualTo(1002);
        assertThat(productionOrderService.detail(id).getNodes().getFirst().getPlanStart()).isEqualTo(req.getNodes().getFirst().getPlanStart());
        nodeService.report(id, d.getNodes().getFirst().getId(), nodeReport(NodeReportAction.START, LocalDate.now(), null));
        assertThat(productionOrderService.detail(id).getPlanRevision()).isEqualTo(1);
        req.setPlanRevision(1);
        assertThat(nodeService.updatePlan(id, req)).isEqualTo(1);
    }

    @Test
    void receiptRetryPreservesOriginalResult_andRealSecondBatchRequiresNewId() {
        Fixture f = fixture();
        long id = approvedProduction(f);
        long item = productionOrderService.detail(id).getItems().getFirst().getId();
        ReceiptReq req = receiptReq(Map.of(item, 10), item, 0);
        var first = receiptService.receive(id, req);
        assertThat(receiptService.receive(id, req)).isEqualTo(first);
        assertThat(receiptService.list(id)).hasSize(1);
        assertThat(productionOrderService.detail(id).getReturnedQty()).isEqualTo(10);
        req.getItems().getFirst().setQty(11);
        assertThatThrownBy(() -> receiptService.receive(id, req)).isInstanceOf(BizException.class).extracting("code").isEqualTo(1002);
        req.getItems().getFirst().setQty(10); req.setClientActionId(java.util.UUID.randomUUID());
        assertThat(receiptService.receive(id, req).id()).isNotEqualTo(first.id());
        assertThat(productionOrderService.detail(id).getReturnedQty()).isEqualTo(20);
    }

    private long approvedProduction(Fixture f) {
        LocalDate commit = LocalDate.now().plusDays(60);
        long order = approvedOrder(f, commit);
        long id = scheduleService.schedule(scheduleReq(f, order, commit.minusDays(10),
                Map.of(orderLine(order, f.skuM), 60, orderLine(order, f.skuL), 40))).getFirst().id();
        productionOrderService.act(id, "submit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        productionOrderService.act(id, "audit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        return id;
    }

    // ---------------------------------------------------------------- 数据

    record Fixture(long company, long user, long warehouse, long factory, long style, long skuM, long skuL, long sku) {
    }

    /** 主体、跟单（默认主体）、物料仓、合作中的成衣厂（带工厂仓）、面料商、一个款（一色两码）、一种面料、生效的生产 BOM（1.5 米 / 件，我方采购） */
    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "R" + s.substring(1), "IT 生产主体" + s, "IT 生产主体" + s);
        long user = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-prd-" + s, "IT 生产" + s, company);
        long warehouse = jdbcTemplate.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITRW" + s, "IT 物料仓" + s);
        long fabricSupplier = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITRS" + s, "IT 面料厂" + s + "有限公司", "IT 面料厂" + s);
        long factory = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITRF" + s, "IT 制衣厂" + s + "有限公司", "IT 制衣" + s);
        jdbcTemplate.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'GARMENT')", factory);
        jdbcTemplate.update("INSERT INTO bas_warehouse (code, name, type, factory_id) VALUES (?, ?, 'FACTORY', ?)",
                "ITGC" + s, "IT 制衣" + s + "仓", factory);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 生产尺码" + s);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')",
                sizeGroup, sizeGroup);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 生产品类" + s);
        String styleCode = "ITPR" + s;
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status,
                                       default_factory_id)
                VALUES (?, 'IT 生产款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED', ?) RETURNING id""",
                Long.class, styleCode, category, sizeGroup, factory);
        long color = jdbcTemplate.queryForObject("INSERT INTO bas_color (code, name) VALUES (?, ?) RETURNING id", Long.class,
                code3(), "IT 生产色" + s);
        jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1)", style, color);
        long skuM = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, styleCode + "-A-M", color);
        long skuL = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'L') RETURNING id",
                Long.class, style, styleCode + "-A-L", color);
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 生产物料分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct,
                                          default_supplier_id, ref_price)
                VALUES (?, 'IT 生产面料', ?, 'FABRIC', 'METER', 'METER', 0, ?, 20) RETURNING id""",
                Long.class, "ITRM" + s, materialCategory, fabricSupplier);
        long sku = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITRM" + s + "-A", color);
        long pack = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id",
                Long.class, style);
        long item = jdbcTemplate.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit, supplier_id)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER', ?) RETURNING id""", Long.class, pack, material, fabricSupplier);
        jdbcTemplate.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)", item, color, sku);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, "jti-it"), null, PERMS.stream().map(SimpleGrantedAuthority::new).toList()));
        return new Fixture(company, user, warehouse, factory, style, skuM, skuL, sku);
    }

    /** 已审核的订单：M 60 件、L 40 件 */
    private long approvedOrder(Fixture f, LocalDate commitDate) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCompanyId(f.company);
        req.setOrderType("STOCK");
        req.setOrderDate(LocalDate.now());
        req.setRequiredDate(commitDate);
        req.setSalesId(f.user);
        req.setMerchId(f.user);
        req.setCurrency("CNY");
        req.setItems(List.of(orderItem(f.skuM, 60), orderItem(f.skuL, 40)));
        long id = orderService.create(req).id();
        orderService.act(id, "submit", new DocActionReq(0, null));
        orderService.audit(id, new OrderAuditReq(1, commitDate));
        return id;
    }

    private static OrderItemReq orderItem(long skuId, int qty) {
        OrderItemReq i = new OrderItemReq();
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        return i;
    }

    /** 一块：这家工厂、清加工 38 元 */
    private static ScheduleReq scheduleReq(Fixture f, long orderId, LocalDate due, Map<Long, Integer> qty) {
        ScheduleReq.Block b = new ScheduleReq.Block();
        b.setStyleId(f.style);
        b.setFactoryId(f.factory);
        b.setProcessMode("CMT");
        b.setUnitPrice(new BigDecimal("38"));
        b.setDueDate(due);
        b.setMerchId(f.user);
        b.setItems(qty.entrySet().stream().map(e -> {
            ScheduleReq.Item i = new ScheduleReq.Item();
            i.setOrderItemId(e.getKey());
            i.setQty(e.getValue());
            return i;
        }).toList());
        ScheduleReq req = new ScheduleReq();
        req.setOrderId(orderId);
        req.setBlocks(List.of(b));
        return req;
    }

    private static NodeReportReq nodeReport(NodeReportAction action, LocalDate start, Integer doneQty) {
        NodeReportReq r = new NodeReportReq();
        r.setAction(action);
        r.setActualStart(start);
        r.setDoneQty(doneQty);
        return r;
    }

    private IssueReq issueReq(LocalDate newExpect) {
        IssueReq r = new IssueReq();
        r.setIssueType("SHORTAGE");
        r.setDescription("辅料没到");
        r.setPhotos(List.of(photo("issue")));
        r.setImpactDelivery(true);
        r.setNewExpectDate(newExpect);
        return r;
    }

    private ReceiptReq receiptReq(Map<Long, Integer> qty, long defectItem, int defect) {
        ReceiptReq r = new ReceiptReq();
        r.setClientActionId(java.util.UUID.randomUUID());
        r.setReceiptDate(LocalDate.now());
        r.setPhotos(List.of(photo("receipt")));
        r.setItems(qty.entrySet().stream().map(e -> {
            ReceiptReq.Item i = new ReceiptReq.Item();
            i.setPrdOrderItemId(e.getKey());
            i.setQty(e.getValue());
            i.setDefectQty(e.getKey() == defectItem ? defect : 0);
            return i;
        }).toList());
        return r;
    }

    private AttachmentReq photo(String name) {
        AttachmentReq a = new AttachmentReq();
        a.setUrl(signer.sign("/files/2026/11/17/" + name + ".jpg"));
        a.setName(name + ".jpg");
        a.setSize(2048L);
        return a;
    }

    /** 领料、退料单，建好马上过账（数量是库存单位）；物料仓用这次建的（默认取的是库里第一个） */
    private static InvDocFromSourceReq stockReq(InvDocType type, Fixture f, String qty) {
        InvDocFromSourceReq.Item i = new InvDocFromSourceReq.Item();
        i.setMaterialSkuId(f.sku);
        i.setQty(new BigDecimal(qty));
        InvDocFromSourceReq req = new InvDocFromSourceReq();
        req.setClientActionId(java.util.UUID.randomUUID());
        req.setDocType(type);
        if (type == InvDocType.ISSUE_OUT) {
            req.setFromWarehouseId(f.warehouse);
        } else {
            req.setToWarehouseId(f.warehouse);
        }
        req.setItems(List.of(i));
        req.setPost(true);
        return req;
    }

    private long orderLine(long orderId, long styleSkuId) {
        return jdbcTemplate.queryForObject("SELECT id FROM ord_order_item WHERE order_id = ? AND style_sku_id = ?", Long.class,
                orderId, styleSkuId);
    }

    private long prdItem(long prdId, long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT id FROM prd_order_item WHERE prd_order_id = ? AND order_item_id = ?", Long.class,
                prdId, orderItemId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ord_order WHERE id = ?", String.class, orderId);
    }

    private int scheduled(long orderId) {
        return jdbcTemplate.queryForObject("SELECT sum(scheduled_qty) FROM ord_order_item WHERE order_id = ?", Integer.class, orderId);
    }

    /** bas_color 的编码是 3 位、全局唯一 */
    private static String code3() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "" + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
    }
}
