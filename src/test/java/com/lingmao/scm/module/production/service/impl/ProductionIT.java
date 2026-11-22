package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.dto.InvDocCreatedVO;
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
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.dto.OrderProgressRowVO;
import com.lingmao.scm.module.production.dto.ProductionBriefVO;
import com.lingmao.scm.module.production.dto.ProductionDetailVO;
import com.lingmao.scm.module.production.dto.ProductionNodeVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.dto.SchedulePrefillVO;
import com.lingmao.scm.module.production.dto.ScheduleQuoteVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.entity.NodeReportAction;
import com.lingmao.scm.module.production.service.OrderProgressService;
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
            "production:board:list", "production:mobile:list", "inventory:doc:add", "inventory:doc:audit", "techpack:cost:query", "order:price:query", "production:price:query");

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
    private OrderProgressService progressService;
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
        TestIdentity.clear();
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

        // 超排：M 订单 60，最多 ⌊60 × 1.05⌋ = 63
        long lineM = orderLine(orderId, f.skuM);
        long lineL = orderLine(orderId, f.skuL);
        assertThatThrownBy(() -> scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(lineM, 64, lineL, 40))))
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
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(prdId, "audit", new DocActionReq(productionOrderService.detail(prdId).getVersion(), null)));
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
        postStock(prdId, stockReq(InvDocType.ISSUE_OUT, f, "150"));
        assertThat(productionOrderService.detail(prdId).getIssueStatus()).isEqualTo("DONE");
        assertThatThrownBy(() -> invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId, stockReq(InvDocType.RETURN_IN, f, "200")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));
        postStock(prdId, stockReq(InvDocType.RETURN_IN, f, "10"));
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

        // 回货：M 60、L 39（次品 2）= 99 ≥ 97 → 已完成，订单已完成；再回 M 4 超过 ⌊60 × 1.05⌋ = 63 → 1004
        long itemM = prdItem(prdId, lineM);
        long itemL = prdItem(prdId, lineL);
        var receipt = receiptService.receive(prdId, receiptReq(Map.of(itemM, 60, itemL, 39), itemL, 2));
        assertThat(receipt.status()).isEqualTo("DONE");
        assertThat(orderStatus(orderId)).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("SELECT sum(returned_qty) FROM ord_order_item WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(99);
        assertThatThrownBy(() -> receiptService.receive(prdId, receiptReq(Map.of(itemM, 4), itemM, 0)))
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
     * docs/api/05 §3.4.1 / Q12：BUY 不必然免领料；本例在订单锁版前准备全部厂购料的 BOM，
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
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(prdId, "audit", new DocActionReq(productionOrderService.detail(prdId).getVersion(), null)));
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
                    assertThat(e.getMessage()).contains("没有自购的料");
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

    /**
     * RFC 013 §4（Q09）：登记裁床数。M 件数 60 裁 50：最多回 ⌊50 × 1.05⌋ = 52；完成按裁床数算（M 50 + L 40 = 90 ≥ 90 × 97%）；
     * 裁床数超过 ⌊件数 × 1.05⌋ 400；完成以后不能再登记
     */
    @Test
    void cutQty_limitsReceiptsAndCompletion() {
        Fixture f = fixture();
        long id = approvedProduction(f);
        var items = productionOrderService.detail(id).getItems();
        long itemM = items.stream().filter(i -> i.getStyleSkuId() == f.skuM).findFirst().orElseThrow().getId();
        long itemL = items.stream().filter(i -> i.getStyleSkuId() == f.skuL).findFirst().orElseThrow().getId();

        assertThatThrownBy(() -> receiptService.registerCut(id, cutReq(itemM, 64))).isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
        receiptService.registerCut(id, cutReq(itemM, 50));
        assertThat(productionOrderService.detail(id).getItems()).filteredOn(i -> i.getId() == itemM).singleElement().satisfies(i -> {
            assertThat(i.getCutQty()).isEqualTo(50);
            assertThat(i.getRemainQty()).isEqualTo(50);
            assertThat(i.getMaxReceiveQty()).isEqualTo(52);
        });
        assertThat(jdbcTemplate.queryForObject("SELECT remark FROM biz_doc_log WHERE doc_type = 'PRODUCTION_ORDER' AND doc_id = ? "
                + "AND action = 'cut'", String.class, id)).contains("未登记 → 50");

        assertThatThrownBy(() -> receiptService.receive(id, receiptReq(Map.of(itemM, 53), itemM, 0)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));
        assertThat(receiptService.receive(id, receiptReq(Map.of(itemM, 50, itemL, 40), itemL, 0)).status()).isEqualTo("DONE");
        assertThatThrownBy(() -> receiptService.registerCut(id, cutReq(itemM, 55))).isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(1000);
    }

    /** 品类线（RFC 013 §2）：加工厂要做款的线；别的线的人列表里没有、详情 404 */
    @Test
    void productLine_factoryMustServe_andHiddenFromOtherLines() {
        Fixture f = fixture();
        LocalDate commit = LocalDate.now().plusDays(60);
        long orderId = approvedOrder(f, commit);
        jdbcTemplate.update("UPDATE sup_supplier SET product_lines = '{FOOTWEAR}' WHERE id = ?", f.factory);

        ScheduleQuoteVO quote = scheduleService.quote(orderId, f.style, f.factory);
        assertThat(quote.usable()).isFalse();
        assertThat(quote.reason()).contains("不做服装");
        Map<Long, Integer> qty = Map.of(orderLine(orderId, f.skuM), 60);
        assertThatThrownBy(() -> scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), qty)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getData().toString()).contains("不做服装");
                });

        jdbcTemplate.update("UPDATE sup_supplier SET product_lines = '{APPAREL,FOOTWEAR}' WHERE id = ?", f.factory);
        long id = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), qty)).getFirst().id();
        ProductionDetailVO d = productionOrderService.detail(id);
        assertThat(d.getProductLine()).isEqualTo("APPAREL");
        ProductionQuery q = new ProductionQuery();
        q.setKind("OUTSOURCE");
        q.setKeyword(d.getNo());
        assertThat(productionOrderService.page(q).getList()).extracting(ProductionRowVO::getId).containsExactly(id);

        List<String> footwear = new java.util.ArrayList<>(PERMS);
        footwear.add("line:FOOTWEAR");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(f.user, "jti-it"), null, footwear.stream().map(SimpleGrantedAuthority::new).toList()));
        assertThat(productionOrderService.page(q).getList()).isEmpty();
        assertThatThrownBy(() -> productionOrderService.detail(id))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(404));
    }

    /**
     * 订单进度总览（RFC 016 §1）：一张订单一个款两个 SKU，分两次排给同一家工厂 → 一行、两个生产单小块；
     * 交期挪到临期、逾期，生产单交期过了算生产有风险；分段条数跟着变。别人（本人范围）、别的品类线看不到
     */
    @Test
    void orderProgress_rowsRiskSegmentsAndScope() {
        Fixture f = fixture();
        LocalDate commit = LocalDate.now().plusDays(30);
        long orderId = approvedOrder(f, commit);
        OrderProgressQuery q = new OrderProgressQuery();
        q.setKeyword(jdbcTemplate.queryForObject("SELECT no FROM ord_order WHERE id = ?", String.class, orderId));

        OrderProgressRowVO row = progressService.page(q).getList().getFirst();
        assertThat(progressService.page(q).getList()).hasSize(1);
        // 品类线筛选（走查 T-120e64）：订单的线筛得到，别的线筛不到
        q.setProductLine(jdbcTemplate.queryForObject("SELECT product_line FROM ord_order WHERE id = ?", String.class, orderId));
        assertThat(progressService.page(q).getList()).hasSize(1);
        q.setProductLine("NO_SUCH_LINE");
        assertThat(progressService.page(q).getList()).isEmpty();
        q.setProductLine(null);
        assertThat(row.getStyleId()).isEqualTo(f.style);
        assertThat(row.getQty()).isEqualTo(100);
        assertThat(row.getScheduledQty()).isZero();
        assertThat(row.getDaysLeft()).isEqualTo(30);
        assertThat(row.getProductions()).isEmpty();
        assertThat(row.getRisk().unscheduled()).isTrue();
        assertThat(row.getRisk().late() || row.getRisk().near() || row.getRisk().production()).isFalse();

        long m = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(orderLine(orderId, f.skuM), 60)))
                .getFirst().id();
        long l = scheduleService.schedule(scheduleReq(f, orderId, commit.minusDays(10), Map.of(orderLine(orderId, f.skuL), 40)))
                .getFirst().id();
        row = progressService.page(q).getList().getFirst();
        assertThat(row.getScheduledQty()).isEqualTo(100);
        assertThat(row.getRisk().unscheduled()).isFalse();
        assertThat(row.getProductions()).extracting(ProductionBriefVO::id).containsExactly(m, l);
        assertThat(row.getProductions()).extracting(ProductionBriefVO::status).containsOnly("DRAFT");
        assertThat(progressService.counts(q)).isEqualTo(new OrderProgressCounts(1, 0, 0, 0, 0));

        // 没排完按 SKU 看：M 多排的 3 件抵不掉 L 少排的 3 件（订单的排单按钮、完成条件也是逐个 SKU 比）
        jdbcTemplate.update("UPDATE ord_order_item SET scheduled_qty = scheduled_qty + 3 WHERE id = ?", orderLine(orderId, f.skuM));
        jdbcTemplate.update("UPDATE ord_order_item SET scheduled_qty = scheduled_qty - 3 WHERE id = ?", orderLine(orderId, f.skuL));
        assertThat(progressService.page(q).getList().getFirst().getRisk().unscheduled()).isTrue();
        jdbcTemplate.update("UPDATE ord_order_item SET scheduled_qty = scheduled_qty - 3 WHERE id = ?", orderLine(orderId, f.skuM));
        jdbcTemplate.update("UPDATE ord_order_item SET scheduled_qty = scheduled_qty + 3 WHERE id = ?", orderLine(orderId, f.skuL));

        // 生产单是别人跟单、别人建的：本人范围的我能看到订单，小块照样列
        long other = jdbcTemplate.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "it-prg-" + orderId, "IT 别人" + orderId);
        jdbcTemplate.update("UPDATE prd_order SET create_by = ?, merch_id = ? WHERE id = ?", other, other, l);
        assertThat(progressService.page(q).getList().getFirst().getProductions()).extracting(ProductionBriefVO::id).containsExactly(m, l);

        jdbcTemplate.update("UPDATE ord_order SET commit_date = ? WHERE id = ?", LocalDate.now().plusDays(3), orderId);
        assertThat(progressService.page(q).getList().getFirst().getRisk().near()).isTrue();
        q.setSegment("NEAR");
        assertThat(progressService.page(q).getList()).hasSize(1);
        q.setSegment("LATE");
        assertThat(progressService.page(q).getList()).isEmpty();

        jdbcTemplate.update("UPDATE ord_order SET commit_date = ? WHERE id = ?", LocalDate.now().minusDays(1), orderId);
        assertThat(progressService.page(q).getList()).singleElement().satisfies(r -> {
            assertThat(r.getRisk().late()).isTrue();
            assertThat(r.getDaysLeft()).isEqualTo(-1);
        });

        // 生产单审核后交期过了：这一行生产有风险，小块上是逾期
        productionOrderService.act(m, "submit", new DocActionReq(productionOrderService.detail(m).getVersion(), null));
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(m, "audit",
                new DocActionReq(productionOrderService.detail(m).getVersion(), null)));
        jdbcTemplate.update("UPDATE prd_order SET due_date = ? WHERE id = ?", LocalDate.now().minusDays(2), m);
        q.setSegment("PRODUCTION");
        assertThat(progressService.page(q).getList()).singleElement().satisfies(r -> {
            assertThat(r.getRisk().production()).isTrue();
            assertThat(r.getProductions().getFirst().risk().late()).isTrue();
            assertThat(r.getProductions().get(1).risk().late()).isFalse();
        });
        q.setSegment(null);
        assertThat(progressService.counts(q)).isEqualTo(new OrderProgressCounts(1, 0, 0, 1, 1));

        // 排序按风险分，不是按交期：交期远但节点延期的（4 分）排在临期的（1 分）前面；不在页上的款的生产单不取
        long nearOrder = approvedOrder(f, commit);
        jdbcTemplate.update("UPDATE ord_order SET commit_date = ? WHERE id = ?", LocalDate.now().plusDays(3), nearOrder);
        long nodeLateOrder = approvedOrder(f, commit);
        long p3 = scheduleService.schedule(scheduleReq(f, nodeLateOrder, commit.minusDays(10), Map.of(orderLine(nodeLateOrder, f.skuM), 60)))
                .getFirst().id();
        productionOrderService.act(p3, "submit", new DocActionReq(productionOrderService.detail(p3).getVersion(), null));
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(p3, "audit",
                new DocActionReq(productionOrderService.detail(p3).getVersion(), null)));
        jdbcTemplate.update("UPDATE prd_node SET plan_start = ?, plan_finish = ? WHERE prd_order_id = ?", LocalDate.now().minusDays(2), LocalDate.now().minusDays(1), p3);
        OrderProgressQuery two = new OrderProgressQuery();
        two.setKeyword(orderNo(nearOrder) + " " + orderNo(nodeLateOrder));
        assertThat(progressService.page(two).getList()).extracting(OrderProgressRowVO::getOrderId).containsExactly(nodeLateOrder, nearOrder);
        assertThat(progressService.page(two).getList().getFirst()).satisfies(r -> {
            assertThat(r.getRisk().nodeLate()).isTrue();
            assertThat(r.getRisk().unscheduled()).isTrue();
            assertThat(r.getProductions()).extracting(ProductionBriefVO::id).containsExactly(p3);
        });
        assertThat(progressService.counts(two)).isEqualTo(new OrderProgressCounts(2, 2, 1, 0, 1));
        // 一次查超过 200 个：400，不是 500
        OrderProgressQuery many = new OrderProgressQuery();
        many.setKeyword(String.join(" ", java.util.stream.IntStream.range(0, 201).mapToObj(i -> "DD" + i).toList()));
        assertThatThrownBy(() -> progressService.page(many))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400));

        // 别人（本人范围）看不到；只有鞋类的人看不到服装的单
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(other, "jti-it"), null, TestIdentity.withLines(PERMS).stream().map(SimpleGrantedAuthority::new).toList()));
        assertThat(progressService.page(q).getList()).isEmpty();
        assertThat(progressService.counts(q).total()).isZero();
        List<String> footwear = new java.util.ArrayList<>(PERMS);
        footwear.add("line:FOOTWEAR");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(f.user, "jti-it"), null, footwear.stream().map(SimpleGrantedAuthority::new).toList()));
        assertThat(progressService.page(q).getList()).isEmpty();
    }

    private static com.lingmao.scm.module.production.dto.CutQtyReq cutReq(long item, Integer cut) {
        var i = new com.lingmao.scm.module.production.dto.CutQtyReq.Item();
        i.setPrdOrderItemId(item);
        i.setCutQty(cut);
        var r = new com.lingmao.scm.module.production.dto.CutQtyReq();
        r.setItems(List.of(i));
        return r;
    }

    private long approvedProduction(Fixture f) {
        LocalDate commit = LocalDate.now().plusDays(60);
        long order = approvedOrder(f, commit);
        long id = scheduleService.schedule(scheduleReq(f, order, commit.minusDays(10),
                Map.of(orderLine(order, f.skuM), 60, orderLine(order, f.skuL), 40))).getFirst().id();
        productionOrderService.act(id, "submit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(id, "audit", new DocActionReq(productionOrderService.detail(id).getVersion(), null)));
        return id;
    }

    // ---------------------------------------------------------------- 数据

    record Fixture(long company, long user, long warehouse, long factory, long style, long skuM, long skuL, long sku) {
    }

    /** 主体、跟单（默认主体）、物料仓、合作中的成衣厂（带工厂仓）、面料商、一个款（一色两码）、一种面料、生效的生产 BOM（1.5 米 / 件，自购） */
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
        long color = TestColors.insert(jdbcTemplate, "IT 生产色" + s);
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
                new LoginUser(user, "jti-it"), null, TestIdentity.withLines(PERMS).stream().map(SimpleGrantedAuthority::new).toList()));
        TestIdentity.grant(jdbcTemplate, user, PERMS);
        TestIdentity.company(company);
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
        TestIdentity.asAuditor(jdbcTemplate, () -> orderService.audit(id, new OrderAuditReq(1, commitDate)));
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

    /** 领料、退料单：建草稿，换审核人过账（自己建的单不能自己审，甲方 11-21 答 Q10） */
    private void postStock(long prdId, InvDocFromSourceReq req) {
        InvDocCreatedVO doc = invDocService.fromSource(BizType.PRODUCTION_ORDER, prdId, req);
        TestIdentity.asAuditor(jdbcTemplate, () -> invDocService.act(doc.id(), "audit", new DocActionReq(doc.version(), null)));
    }

    /** 领料、退料单的草稿（数量是库存单位）；物料仓用这次建的（默认取的是库里第一个） */
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
        return req;
    }

    private String orderNo(long orderId) {
        return jdbcTemplate.queryForObject("SELECT no FROM ord_order WHERE id = ?", String.class, orderId);
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

}
