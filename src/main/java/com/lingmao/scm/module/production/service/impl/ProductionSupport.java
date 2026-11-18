package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.dto.ProductionProgress;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.RiskVO;
import com.lingmao.scm.module.production.entity.MaterialIssueStatus;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.production.entity.ProductionNode;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.module.production.mapper.ProductionNodeMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/**
 * 生产单几个服务共用的：取单（数据范围）、锁单（先订单后生产单）、按进度重算状态、回写订单、领料状态、列表行、按钮。
 * 锁的顺序（docs/api/05 §3.9）：要回写订单的动作先 OrderService.lockForProduction，再锁生产单；只动生产单自己的只锁生产单
 */
@Component
@RequiredArgsConstructor
class ProductionSupport {

    static final String NOT_FOUND = "生产单不存在";
    static final String STALE = "这张单已经被别人改过，请刷新后再操作";

    private final ProductionOrderMapper prdMapper;
    private final ProductionOrderItemMapper itemMapper;
    private final ProductionNodeMapper nodeMapper;
    private final OrderService orderService;
    private final MrpService mrpService;
    private final SupplierService supplierService;
    private final UserService userService;
    private final AttachmentService attachmentService;
    private final ApprovalService approvalService;
    private final DocLogService docLogService;
    private final Clock clock;

    LocalDate today() {
        return LocalDate.now(clock);
    }

    LocalDate nearUntil() {
        return today().plusDays(ProductionRules.NEAR_DAYS);
    }

    // ---------- 取单、锁单 ----------

    ProductionOrder getVisible(long id) {
        return visible(prdMapper.selectById(id));
    }

    /** 只锁生产单：报进度、改节点计划、上报异常、改草稿、提交这种不回写订单的 */
    ProductionOrder lock(long id) {
        return visible(prdMapper.lockById(id));
    }

    /** 先锁订单、再锁生产单（要回写订单的：审核、反审核、回货、结案、删、作废） */
    Locked lockWithOrder(long id) {
        ProductionOrder p = getVisible(id);
        OrderForProduction order = orderService.lockForProduction(p.getOrderId());
        return new Locked(order, lock(id));
    }

    record Locked(OrderForProduction order, ProductionOrder prd) {
    }

    /** 数据范围（PRD §3.2）：制单人、生产跟单；待审核时轮到审的人也能看（规则加的审批人不一定在范围里） */
    ProductionOrder visible(ProductionOrder p) {
        if (p == null) {
            throw new BizException(404, NOT_FOUND);
        }
        if (!DataScope.current(false).canSee(p.getCreateBy(), p.getMerchId())
                && !(p.getStatus() == PENDING && isMyTurn(p.getId()))) {
            throw new BizException(404, NOT_FOUND);
        }
        com.lingmao.scm.common.scope.CompanyContext.target(p.getCompanyId());
        return p;
    }

    private boolean isMyTurn(long id) {
        ApprovalView view = approvalService.view(BizType.PRODUCTION_ORDER, id);
        return view != null && view.canPass();
    }

    static boolean isCreator(Long createBy) {
        return createBy != null && createBy.equals(LoginUser.currentUserId());
    }

    /** "外发加工单 JG…"：报错、操作记录里用 */
    static String label(ProductionOrder p) {
        return p.getKind().getLabel() + " " + p.getNo();
    }

    // ---------- 状态 ----------

    /**
     * 按进度重算状态（已下单、生产中、已完成之间换，§2.1），写一条操作记录：变了比较并交换、版本号 +1，记录带上从哪到哪。
     * 返回新状态。只给这三个状态的单调；调之前已经锁住生产单
     */
    DocStatus refreshStatus(ProductionOrder p, String action, String remark) {
        DocStatus to = ProductionRules.byProgress(itemMapper.selectByOrder(p.getId()), nodeMapper.selectByOrder(p.getId()));
        if (to != p.getStatus()) {
            StatusCas.update(prdMapper, new ProductionOrder(), StatusCas.of(ProductionOrder::getId, p.getId(), ProductionOrder::getStatus,
                    p.getStatus(), to).setSql("version = version + 1"));
            docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), action, p.getStatus(), to, remark);
        } else {
            docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), action, remark);
        }
        return to;
    }

    /**
     * 回写订单状态（02 §3.9）：有审核过的生产单是生产中；每行排满了、没作废的生产单都完成或结案了是已完成。调之前已经锁住订单
     */
    void syncOrder(long orderId, String cause) {
        List<ProductionOrder> live = prdMapper.selectLiveByOrder(orderId);
        boolean audited = live.stream().anyMatch(p -> Set.of(APPROVED, RUNNING, DONE, CLOSED).contains(p.getStatus()));
        boolean allFinished = !live.isEmpty() && live.stream().allMatch(p -> p.getStatus() == DONE || p.getStatus() == CLOSED);
        orderService.syncProduction(orderId, new ProductionProgress(audited, allFinished), cause);
    }

    // ---------- 加工厂 ----------

    /** 能不能下大货单：合作中、分类里有成衣厂或加工厂；能是 null，不能是原因。what 是"外发加工单""成品采购单""生产单" */
    static String factoryProblem(SupplierRef f, List<String> categories, String what) {
        if (f.status() != SupplierStatus.ACTIVE) {
            return "加工厂" + f.shortName() + "现在是「" + f.status().getLabel() + "」，不能下" + what;
        }
        if (!SupplierService.isFactory(categories)) {
            return f.shortName() + "的分类里没有成衣厂或加工厂，不能下" + what;
        }
        return null;
    }

    /** 能不能下单的原因，见 factoryProblem；不存在 404 */
    String factoryProblem(long factoryId, String what) {
        SupplierRef f = supplierService.getRef(factoryId);
        return factoryProblem(f, supplierService.categories(List.of(factoryId)).getOrDefault(factoryId, List.of()), what);
    }

    /** 不能下单抛 1000 */
    void checkFactory(long factoryId, ProductionKind kind) {
        String problem = factoryProblem(factoryId, kind.getLabel());
        if (problem != null) {
            throw new BizException(problem);
        }
    }

    // ---------- 领料 ----------

    /** 应领：生产单 → 物料 SKU → 数量（库存单位）。算法同算料（MrpCalculator：件数 × 用量 ×（1 + 损耗率）），按尺码配用量没填的行不算 */
    Map<Long, Map<Long, BigDecimal>> needs(Collection<Long> prdOrderIds) {
        Map<Long, Map<Long, BigDecimal>> out = new HashMap<>();
        if (prdOrderIds.isEmpty()) {
            return out;
        }
        for (ProductionOrderMapper.NeedRow r : prdMapper.selectNeeds(prdOrderIds)) {
            if (r.usage() == null) {
                continue;
            }
            BigDecimal qty = Num.addPct(BigDecimal.valueOf(r.qty()).multiply(r.usage()), r.lossPct());
            out.computeIfAbsent(r.prdOrderId(), k -> new HashMap<>()).merge(r.skuId(), qty, BigDecimal::add);
        }
        out.values().forEach(m -> m.replaceAll((k, v) -> Num.qty(v)));
        return out;
    }

    /** 已领：生产单 → 物料 SKU → 已审核的领料出库 − 退料入库；excludeDocId 不算这张 */
    Map<Long, Map<Long, BigDecimal>> issued(Collection<Long> prdOrderIds, Long excludeDocId) {
        Map<Long, Map<Long, BigDecimal>> out = new HashMap<>();
        if (prdOrderIds.isEmpty()) {
            return out;
        }
        prdMapper.selectIssued(prdOrderIds, excludeDocId).forEach(r ->
                out.computeIfAbsent(r.prdOrderId(), k -> new HashMap<>()).put(r.skuId(), Num.qty(r.qty())));
        return out;
    }

    /** 排单时定的领料状态：锁定的生产 BOM 里有自购的料是 NONE，没有是 null（不用领料） */
    MaterialIssueStatus initialIssueStatus(long prdOrderId) {
        return needs(List.of(prdOrderId)).isEmpty() ? null : MaterialIssueStatus.NONE;
    }

    /**
     * 领料、退料过账以后（§3.4.3）：重算这张单的领料状态；把这张订单（和一起算料的订单）的已领回写到算料单，
     * 料都领完了的订单的算料单自动关闭。调之前已经锁住生产单
     */
    void syncIssued(ProductionOrder p) {
        long id = p.getId();
        MaterialIssueStatus status = ProductionRules.issueStatus(needs(List.of(id)).getOrDefault(id, Map.of()),
                issued(List.of(id), null).getOrDefault(id, Map.of()));
        if (status != p.getIssueStatus()) {
            prdMapper.updateIssueStatus(id, status == null ? null : status.name());
        }
        Set<Long> orders = mrpService.calcPeers(List.of(p.getOrderId()));
        Map<Long, Map<Long, BigDecimal>> byOrder = new HashMap<>();
        orders.forEach(o -> byOrder.put(o, new HashMap<>()));
        prdMapper.selectIssuedByOrders(orders).forEach(r -> byOrder.get(r.orderId()).put(r.skuId(), r.qty()));
        mrpService.syncIssued(byOrder, new HashSet<>(prdMapper.selectIssuedOrders(orders)));
    }

    // ---------- 列表行 ----------

    /** 列表、看板、导出的行：节点进度条、当前节点、剩余天数、风险、款式主图、跟单名字、按钮 */
    void fillRows(List<ProductionRowVO> rows, boolean withActions) {
        if (rows.isEmpty()) {
            return;
        }
        LocalDate today = today();
        List<Long> ids = rows.stream().map(ProductionRowVO::getId).toList();
        Map<Long, List<ProductionNode>> nodes = nodeMapper.selectByOrders(ids).stream()
                .collect(Collectors.groupingBy(ProductionNode::getPrdOrderId));
        Map<Long, String> names = userService.nicknames(rows.stream().map(ProductionRowVO::getMerchId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE, rows.stream().map(ProductionRowVO::getStyleId).collect(Collectors.toSet()),
                Attachment.IMAGE);
        Set<Long> mine = withActions && rows.stream().anyMatch(r -> PENDING.name().equals(r.getStatus()))
                ? new HashSet<>(approvalService.waitingForMe(BizType.PRODUCTION_ORDER)) : Set.of();
        for (ProductionRowVO r : rows) {
            DocStatus status = DocStatus.valueOf(r.getStatus());
            List<ProductionNode> ns = nodes.getOrDefault(r.getId(), List.of());
            r.setNodes(ProductionRules.dots(ns, today));
            r.setCurrentNodeName(ProductionRules.currentNodeName(ns));
            r.setDaysLeft(ProductionRules.daysLeft(status, r.getDueDate(), r.getExpectDate(), today));
            r.setRisk(new RiskVO(r.isRiskLate(), r.isRiskNear(), r.isRiskNodeLate(), r.isRiskIssue(), r.isRiskUpstream()));
            r.setImage(images.get(r.getStyleId()));
            r.setMerchName(names.get(r.getMerchId()));
            if (withActions) {
                MaterialIssueStatus issue = r.getIssueStatus() == null ? null : MaterialIssueStatus.valueOf(r.getIssueStatus());
                r.setActions(actions(status, new Ctx(isCreator(r.getCreateBy()),
                        new ApprovalView(0, List.of(), null, mine.contains(r.getId())), false, issue)));
            }
        }
    }

    /**
     * 算按钮要的业务条件：creator 我是制单人（撤回）；approval 审批块；blocked 报过进度、有下游（不出反审核，列表不查）；
     * issue 领料状态（null 不用领料，不出领料、退料）
     */
    record Ctx(boolean creator, ApprovalView approval, boolean blocked, MaterialIssueStatus issue) {
    }

    /** 按钮（前端 ProductionAction）。领料、退料看 inventory:doc:add */
    static List<String> actions(DocStatus status, Ctx c) {
        String p = ProductionRules.PERM;
        return DocActions.of(ProductionRules.FLOW, status, p)
                .perm("edit", p + "edit")
                .perm("remove", p + "remove")
                .perm("report", "production:node:edit")
                .perm("editPlan", "production:node:edit")
                .perm("reportIssue", "production:issue:edit")
                .perm("receive", "production:receipt:add")
                .perm("issue", "inventory:doc:add")
                .perm("returnMaterial", "inventory:doc:add")
                .when("withdraw", c.creator())
                .when("unaudit", !c.blocked())
                .when("issue", c.issue() != null)
                .when("returnMaterial", c.issue() == MaterialIssueStatus.PARTIAL || c.issue() == MaterialIssueStatus.DONE)
                .approval(c.approval())
                .list();
    }
}
