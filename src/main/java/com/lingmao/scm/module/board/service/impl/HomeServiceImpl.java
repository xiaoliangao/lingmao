package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.board.dto.DeliveryRow;
import com.lingmao.scm.module.board.dto.HomeKpi;
import com.lingmao.scm.module.board.dto.HomeTodoQuery;
import com.lingmao.scm.module.board.dto.HomeTodos;
import com.lingmao.scm.module.board.dto.TodoCount;
import com.lingmao.scm.module.board.dto.TodoGroup;
import com.lingmao.scm.module.board.dto.TodoItem;
import com.lingmao.scm.module.board.dto.UpstreamList;
import com.lingmao.scm.module.board.dto.UpstreamQuery;
import com.lingmao.scm.module.board.dto.UpstreamRow;
import com.lingmao.scm.module.board.service.HomeService;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.fg.service.FgDocService;
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderChangeRowVO;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderStyleRef;
import com.lingmao.scm.module.order.dto.OrderTotals;
import com.lingmao.scm.module.order.service.OrderChangeService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.BoardCards;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.dto.NodeDot;
import com.lingmao.scm.module.production.dto.OpenIssueVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.service.FactoryLedgerService;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.OrderCutRisk;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.purchase.service.SupplyService;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.PackDraftVO;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 首页（docs/api/06）。每一块问各域自己的列表、条数（Service），条件和点进去的列表一样，数据范围、品类线由各域加；
 * board 只拼文字、排序、截前几条。
 * <p>
 * "负责人"（mine / ownerId，§2.3）：数据范围是"全部"的人默认看所有人的，可以筛到某个负责人；别的人只看负责人是自己的，传什么都一样。
 * 待我审核一组和审核类的数只看"轮到我"，不按负责人筛
 */
@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    /** 每组最多给几条 */
    static final int GROUP_SIZE = 20;
    /** 近期交期：今天起几天内的，最多几条 */
    static final int DELIVERY_DAYS = 7;
    static final int DELIVERY_SIZE = 8;

    /** 生产单列表要分种类查 */
    static final List<String> PRODUCTION_KINDS = List.of("OUTSOURCE", "BUY");

    static final String STYLE_LIST = "style:style:list";
    static final String PACK_LIST = "techpack:pack:list";
    static final String PACK_PUBLISH = "techpack:pack:publish";
    static final String ORDER_LIST = "order:order:list";
    static final String CHANGE_LIST = "order:change:list";
    static final String CHANGE_AUDIT = "order:change:audit";
    static final String PRODUCTION_LIST = "production:order:list";
    static final String PRODUCTION_ADD = "production:order:add";
    static final String PURCHASE_LIST = "purchase:order:list";
    static final String MRP_LIST = "purchase:mrp:list";
    static final String DOC_LIST = "inventory:doc:list";
    static final String DOC_AUDIT = "inventory:doc:audit";
    static final String COUNT_LIST = "inventory:count:list";
    static final String COUNT_AUDIT = "inventory:count:audit";
    static final String STOCK_LIST = "inventory:stock:list";
    static final String SUPPLIER_LIST = "supplier:supplier:list";
    static final String SUPPLIER_ADMIT = "supplier:supplier:admit";
    static final String FG_ADD = "fg:doc:add";

    /** "其他提醒"按这个顺序给（前端 TodoCountKey） */
    static final List<String> COUNT_KEYS = List.of("ORDER_PENDING", "CHANGE_PENDING", "PURCHASE_PENDING", "OUTSOURCE_PENDING",
            "BUY_PENDING", "STOCK_IN_DRAFT", "STOCK_OUT_DRAFT", "COUNT_PENDING", "FG_PENDING", "CONSUME_PENDING", "SUPPLIER_APPROVE", "UNSCHEDULED", "PRODUCTION_LATE",
            "PRODUCTION_NEAR", "PRODUCTION_NODE_LATE", "PRODUCTION_ISSUE", "ARRIVAL_LATE", "ARRIVAL_CUT", "UPSTREAM", "CERT_EXPIRING",
            "REORDER");

    /** 供应商的状态叫法、颜色（和前端 SUPPLIER_STATUS 一样）：待办里的供应商没有 ReferenceService 能描述，这里自己造 DocRef */
    static final StatusLabels<SupplierStatus> SUPPLIER_STATUS = StatusLabels.of(SupplierStatus.class)
            .with(SupplierStatus.PENDING_ADMIT, "待准入", StatusTone.OFF)
            .with(SupplierStatus.ADMITTING, "准入审批中", StatusTone.PENDING)
            .with(SupplierStatus.ACTIVE, "合作中", StatusTone.ACTIVE)
            .with(SupplierStatus.DISABLED, "停用", StatusTone.OFF)
            .with(SupplierStatus.BLACKLISTED, "黑名单", StatusTone.DANGER);

    private final StyleService styleService;
    private final TechPackService techPackService;
    private final OrderService orderService;
    private final OrderChangeService changeService;
    private final ProductionOrderService productionService;
    private final ProductionBoardService productionBoardService;
    private final ProductionIssueService issueService;
    private final PurchaseOrderService purchaseService;
    private final MrpService mrpService;
    private final SupplyService supplyService;
    private final InvDocService invDocService;
    private final CountService countService;
    private final FgDocService fgDocService;
    private final FactoryLedgerService factoryLedgerService;
    private final SupplierService supplierService;
    private final ReferenceService referenceService;
    private final AttachmentService attachmentService;
    private final DictDataService dictDataService;
    private final UpstreamRows upstreamRows;
    private final Clock clock;

    // ---------------------------------------------------------------- 概况

    @Override
    public HomeKpi kpi() {
        return new HomeKpi(can(STYLE_LIST) ? devKpi() : null, can(ORDER_LIST) ? orderKpi() : null,
                can(PRODUCTION_LIST) ? productionKpi() : null, can(PURCHASE_LIST) ? materialKpi() : null);
    }

    private HomeKpi.Dev devKpi() {
        long styles = styleTotal(StyleStatus.DRAFT) + styleTotal(StyleStatus.ENABLED);
        return new HomeKpi.Dev(styles, techPackService.drafts(first(1)).getTotal());
    }

    private long styleTotal(StyleStatus status) {
        StyleQuery q = new StyleQuery();
        q.setStatus(status);
        q.setSize(1);
        return styleService.page(q).getTotal();
    }

    private HomeKpi.Order orderKpi() {
        OrderQuery active = new OrderQuery();
        active.setStatus(List.of(DocStatus.APPROVED, DocStatus.RUNNING));
        OrderTotals totals = orderService.totals(active);
        OrderQuery pending = new OrderQuery();
        pending.setStatus(List.of(DocStatus.PENDING));
        OrderQuery unscheduled = new OrderQuery();
        unscheduled.setUnscheduled(true);
        Long changes = can(CHANGE_LIST)
                ? changeService.counts(new OrderChangeQuery()).getOrDefault(DocStatus.PENDING.name(), 0L) : null;
        return new HomeKpi.Order(totals.qty(), totals.count(), orderService.totals(pending).count(),
                orderService.totals(unscheduled).count(), changes);
    }

    private HomeKpi.Production productionKpi() {
        BoardCards c = productionBoardService.cards(new BoardQuery());
        return new HomeKpi.Production(c.runningQty() + c.waitingQty(), c.runningCount() + c.waitingCount(), c.lateCount(),
                c.nodeLateCount(), c.issueCount());
    }

    private HomeKpi.Material materialKpi() {
        PendingArrivalCounts arrivals = purchaseService.pendingCounts(new PendingArrivalQuery());
        PurchaseQuery pending = new PurchaseQuery();
        pending.setStatus(List.of(DocStatus.PENDING));
        pending.setSize(1);
        Long drafts = null;
        if (can(MRP_LIST)) {
            MrpCalcQuery q = new MrpCalcQuery();
            q.setStatus(List.of(MrpStatus.DRAFT));
            q.setSize(1);
            drafts = mrpService.page(q).getTotal();
        }
        return new HomeKpi.Material(arrivals.all(), arrivals.late(), purchaseService.page(pending).getTotal(), drafts);
    }

    // ---------------------------------------------------------------- 近期交期

    @Override
    public List<DeliveryRow> deliveries() {
        LocalDate today = LocalDate.now(clock);
        LocalDate until = today.plusDays(DELIVERY_DAYS);
        List<DeliveryRow> rows = new ArrayList<>();
        if (can(ORDER_LIST)) {
            rows.addAll(orderDeliveries(today, until));
        }
        if (can(PRODUCTION_LIST)) {
            rows.addAll(productionDeliveries(today, until));
        }
        return rows.stream().sorted(Comparator.comparing(DeliveryRow::dueDate).thenComparing(r -> r.ref().no()))
                .limit(DELIVERY_SIZE).toList();
    }

    /** 已审核、生产中的需求订单，交期 = 承诺交期 ?? 要求交期 */
    private List<DeliveryRow> orderDeliveries(LocalDate today, LocalDate until) {
        OrderQuery q = new OrderQuery();
        q.setStatus(List.of(DocStatus.APPROVED, DocStatus.RUNNING));
        q.setDueDateTo(until);
        q.setDueFirst(true);
        q.setSize(DELIVERY_SIZE);
        List<OrderRowVO> orders = orderService.page(q).getList();
        Map<Long, DocRef> refs = refs(BizType.ORDER, orders, OrderRowVO::getId);
        Map<Long, StyleRef> styles = styleService.refs(orders.stream().flatMap(o -> o.getStyles().stream())
                .map(OrderStyleRef::styleId).collect(Collectors.toSet()));
        return orders.stream().map(o -> {
            LocalDate due = due(o);
            String names = o.getStyles().stream().map(s -> styles.get(s.styleId())).filter(Objects::nonNull).map(StyleRef::name)
                    .distinct().collect(Collectors.joining("、"));
            return new DeliveryRow(refs.get(o.getId()), names, firstImage(o), due, ChronoUnit.DAYS.between(today, due),
                    o.getReturnedQty(), o.getTotalQty() == null ? 0 : o.getTotalQty());
        }).toList();
    }

    /** 已下单、生产中的生产单（两种），交期 = 预计交期 ?? 交期 */
    private List<DeliveryRow> productionDeliveries(LocalDate today, LocalDate until) {
        List<ProductionRowVO> rows = new ArrayList<>();
        for (String kind : PRODUCTION_KINDS) {
            ProductionQuery q = activeProduction(kind);
            q.setDueDateTo(until);
            q.setDueFirst(true);
            q.setSize(DELIVERY_SIZE);
            rows.addAll(productionService.page(q).getList());
        }
        Map<Long, DocRef> refs = refs(BizType.PRODUCTION_ORDER, rows, ProductionRowVO::getId);
        return rows.stream().map(p -> {
            LocalDate due = due(p);
            return new DeliveryRow(refs.get(p.getId()), BoardTexts.dot(p.getStyleName(), p.getFactoryShortName()), p.getImage(), due,
                    ChronoUnit.DAYS.between(today, due), p.getReturnedQty() - p.getDefectQty(), p.getTotalQty());   // 只算良品
        }).toList();
    }

    // ---------------------------------------------------------------- 待办

    @Override
    public HomeTodos todos(HomeTodoQuery query) {
        boolean canSeeAll = canSeeAll();
        Long owner = owner(canSeeAll, query.isMine(), query.getOwnerId());
        Map<String, Long> counts = new HashMap<>();
        List<TodoGroup> groups = new ArrayList<>();
        add(groups, audit(counts));
        add(groups, productionLate(owner, counts));
        add(groups, cutRisk(owner));
        add(groups, arrivalLate(owner, counts));
        add(groups, upstream(owner, counts));
        add(groups, unscheduled(owner, counts));
        add(groups, issues(owner));
        add(groups, certs(owner, counts));
        if (can(STOCK_LIST) && can(PURCHASE_LIST)) {
            counts.put("REORDER", (long) supplyService.reorderItems().size());
        }
        if (can(FG_ADD)) {
            counts.put("FG_PENDING", fgDocService.pendingReceiptCount());
        }
        if (can("inventory:doc:add") && can("production:ledger:list")) {
            counts.put("CONSUME_PENDING", factoryLedgerService.toSettleCount());   // 待核销耗料（RFC 015 §4.3）
        }
        List<TodoCount> out = COUNT_KEYS.stream().filter(k -> counts.getOrDefault(k, 0L) > 0)
                .map(k -> new TodoCount(k, counts.get(k))).toList();
        return new HomeTodos(groups, out, canSeeAll);
    }

    /** 一条待办和它在组里的排序键（小的在前） */
    private record Ranked(TodoItem item, long rank) {
    }

    private static TodoGroup group(String key, long total, List<Ranked> items) {
        return new TodoGroup(key, total, items.stream().sorted(Comparator.comparingLong(Ranked::rank))
                .limit(GROUP_SIZE).map(Ranked::item).toList());
    }

    private static void add(List<TodoGroup> groups, TodoGroup group) {
        if (group != null && group.total() > 0) {
            groups.add(group);
        }
    }

    /**
     * 待我审核：需求订单、采购单、生产单和对应列表的"待我审核"同一个查询（轮到我这一级）；
     * 变更单、资料包、出入库、盘点、供应商要列表权限加上审核（生效、准入）权限，范围按各自列表。旧的在前。
     * 自己建的单都不列（自己建的单不能自己审，SelfAudit）：前三种在 ApprovalService.waitingForMe 里去掉，变更单、出入库、盘点用 auditForMe
     */
    private TodoGroup audit(Map<String, Long> counts) {
        List<Ranked> items = new ArrayList<>();
        long total = 0;
        if (can(ORDER_LIST)) {
            OrderQuery q = new OrderQuery();
            q.setAuditForMe(true);
            q.setStatus(List.of(DocStatus.PENDING));
            q.setSize(GROUP_SIZE);
            PageResult<OrderRowVO> page = orderService.page(q);
            total += put(counts, "ORDER_PENDING", page.getTotal());
            Map<Long, DocRef> refs = refs(BizType.ORDER, page.getList(), OrderRowVO::getId);
            for (OrderRowVO o : page.getList()) {
                String sub = BoardTexts.dot(BoardTexts.codes(o.getStyles().stream().map(OrderStyleRef::styleCode).toList()),
                        BoardTexts.count(o.getTotalQty() == null ? 0 : o.getTotalQty()) + " 件", "交期 " + BoardTexts.md(due(o)));
                items.add(ranked(refs.get(o.getId()), "需求订单 " + o.getNo(), sub, firstImage(o), o.getCreateTime()));
            }
        }
        if (can(PURCHASE_LIST)) {
            PurchaseQuery q = new PurchaseQuery();
            q.setAuditForMe(true);
            q.setStatus(List.of(DocStatus.PENDING));
            q.setSize(GROUP_SIZE);
            PageResult<PurchaseRowVO> page = purchaseService.page(q);
            total += put(counts, "PURCHASE_PENDING", page.getTotal());
            Map<Long, DocRef> refs = refs(BizType.PURCHASE_ORDER, page.getList(), PurchaseRowVO::getId);
            for (PurchaseRowVO p : page.getList()) {
                String sub = BoardTexts.dot(p.getSupplierName(), p.getItemCount() + " 种料", "交期 " + BoardTexts.md(p.getDeliveryDate()));
                items.add(ranked(refs.get(p.getId()), "采购单 " + p.getNo(), sub, p.getFirstItem() == null ? null
                        : p.getFirstItem().getImage(), p.getCreateTime()));
            }
        }
        if (can(PRODUCTION_LIST)) {
            for (String kind : PRODUCTION_KINDS) {
                ProductionQuery q = new ProductionQuery();
                q.setKind(kind);
                q.setAuditForMe(true);
                q.setStatus(List.of(DocStatus.PENDING));
                q.setSize(GROUP_SIZE);
                PageResult<ProductionRowVO> page = productionService.page(q);
                total += put(counts, kind + "_PENDING", page.getTotal());
                Map<Long, DocRef> refs = refs(BizType.PRODUCTION_ORDER, page.getList(), ProductionRowVO::getId);
                for (ProductionRowVO p : page.getList()) {
                    DocRef ref = refs.get(p.getId());
                    String sub = BoardTexts.dot(p.getFactoryShortName(), p.getStyleName(), BoardTexts.count(p.getTotalQty()) + " 件");
                    items.add(ranked(ref, ref.typeLabel() + " " + p.getNo(), sub, p.getImage(), p.getCreateTime()));
                }
            }
        }
        if (can(CHANGE_LIST) && can(CHANGE_AUDIT)) {
            OrderChangeQuery q = new OrderChangeQuery();
            q.setAuditForMe(true);
            q.setStatus(List.of(DocStatus.PENDING));
            q.setSize(GROUP_SIZE);
            PageResult<OrderChangeRowVO> page = changeService.page(q);
            total += put(counts, "CHANGE_PENDING", page.getTotal());
            Map<Long, DocRef> refs = refs(BizType.ORDER_CHANGE, page.getList(), OrderChangeRowVO::getId);
            for (OrderChangeRowVO c : page.getList()) {
                items.add(ranked(refs.get(c.getId()), "订单变更单 " + c.getNo(), BoardTexts.dot(c.getOrderNo(), c.getChangeTypesText()),
                        null, c.getCreateTime()));
            }
        }
        if (can(PACK_LIST) && can(PACK_PUBLISH)) {
            PageResult<PackDraftVO> page = techPackService.drafts(first(GROUP_SIZE));
            total += page.getTotal();
            Map<Long, DocRef> refs = refs(BizType.TECH_PACK, page.getList(), PackDraftVO::getId);
            Map<Long, AttachmentVO> images = styleImages(page.getList().stream().map(PackDraftVO::getStyleId).toList());
            for (PackDraftVO p : page.getList()) {
                items.add(ranked(refs.get(p.getId()), "资料包 " + p.getStyleCode() + " V" + p.getVersion(),
                        BoardTexts.dot(p.getStyleName(), "草稿待生效"), images.get(p.getStyleId()), p.getCreateTime()));
            }
        }
        if (can(DOC_LIST) && can(DOC_AUDIT)) {
            for (InvDocKind kind : InvDocKind.values()) {
                InvDocQuery q = new InvDocQuery();
                q.setKind(kind);
                q.setAuditForMe(true);
                q.setStatus(List.of(DocStatus.DRAFT));
                q.setSize(GROUP_SIZE);
                PageResult<InvDocRowVO> page = invDocService.page(q);
                total += put(counts, kind == InvDocKind.IN ? "STOCK_IN_DRAFT" : "STOCK_OUT_DRAFT", page.getTotal());
                Map<Long, DocRef> refs = refs(BizType.INV_DOC, page.getList(), InvDocRowVO::getId);
                for (InvDocRowVO d : page.getList()) {
                    DocRef ref = refs.get(d.getId());
                    String warehouse = kind == InvDocKind.IN ? d.getToWarehouseName() : d.getFromWarehouseName();
                    items.add(ranked(ref, ref.typeLabel() + " " + d.getNo(), BoardTexts.dot(warehouse, d.getItemCount() + " 种料"),
                            d.getFirstItem() == null ? null : d.getFirstItem().getImage(), d.getCreateTime()));
                }
            }
        }
        if (can(COUNT_LIST) && can(COUNT_AUDIT)) {
            CountQuery q = new CountQuery();
            q.setAuditForMe(true);
            q.setStatus(List.of(DocStatus.PENDING));
            q.setSize(GROUP_SIZE);
            PageResult<CountRowVO> page = countService.page(q);
            total += put(counts, "COUNT_PENDING", page.getTotal());
            Map<Long, DocRef> refs = refs(BizType.INV_COUNT, page.getList(), CountRowVO::getId);
            for (CountRowVO c : page.getList()) {
                items.add(ranked(refs.get(c.getId()), "物料盘点单 " + c.getNo(), BoardTexts.dot(c.getWarehouseName(), c.getScopeText()),
                        null, c.getCreateTime()));
            }
        }
        if (can(SUPPLIER_LIST) && can(SUPPLIER_ADMIT)) {
            // 准入审批中或者有待批改动，申请人不是我（列表的"待我审批"）
            SupplierQuery q = new SupplierQuery();
            q.setToApprove(true);
            q.setSize(GROUP_SIZE);
            PageResult<SupplierRowVO> page = supplierService.page(q);
            total += put(counts, "SUPPLIER_APPROVE", page.getTotal());
            for (SupplierRowVO s : page.getList()) {
                String what = s.getStatus() == SupplierStatus.ADMITTING ? "供应商准入 " : "供应商资料变更 ";
                items.add(ranked(supplierRef(s), what + supplierName(s), BoardTexts.dot(categories(s), "负责人 " + s.getBuyerName()),
                        null, s.getUpdateTime()));
            }
        }
        return group("AUDIT", total, items);
    }

    /** 生产延期：已下单、生产中的生产单里已逾期或节点延期的，负责人（生产跟单）筛；逾期的在前，逾期久的在前 */
    private TodoGroup productionLate(Long owner, Map<String, Long> counts) {
        if (!can(PRODUCTION_LIST)) {
            return null;
        }
        BoardQuery board = new BoardQuery();
        board.setMerchId(owner);
        BoardCards cards = productionBoardService.cards(board);
        counts.put("PRODUCTION_LATE", cards.lateCount());
        counts.put("PRODUCTION_NEAR", cards.nearCount());
        counts.put("PRODUCTION_NODE_LATE", cards.nodeLateCount());
        counts.put("PRODUCTION_ISSUE", cards.issueCount());

        List<ProductionRowVO> rows = new ArrayList<>();
        long total = 0;
        for (String kind : PRODUCTION_KINDS) {
            ProductionQuery q = activeProduction(kind);
            q.setRisk("DELAYED");
            q.setMerchId(owner);
            q.setSize(GROUP_SIZE);
            PageResult<ProductionRowVO> page = productionService.page(q);
            rows.addAll(page.getList());
            total += page.getTotal();
        }
        Map<Long, DocRef> refs = refs(BizType.PRODUCTION_ORDER, rows, ProductionRowVO::getId);
        List<Ranked> items = new ArrayList<>();
        for (ProductionRowVO p : rows) {
            LocalDate due = due(p);
            boolean late = due.isBefore(LocalDate.now(clock));
            String sub = late ? BoardTexts.dot(p.getFactoryShortName(), "交期 " + BoardTexts.md(due) + " 已过")
                    : BoardTexts.dot(p.getFactoryShortName(), "节点「" + lateNode(p) + "」延期");
            // 逾期的排在节点延期前面，各自交期早的在前
            long rank = (late ? 0 : 1_000_000L) + due.toEpochDay();
            items.add(new Ranked(new TodoItem(refs.get(p.getId()), p.getNo() + " " + p.getStyleName(), sub, p.getImage()), rank));
        }
        return group("PRODUCTION_LATE", total, items);
    }

    /**
     * 晚于开裁（03 待到料的 CUT）：采购员看自己采购单里的行；生产跟单看自己跟的已下单、生产中的外发加工单，
     * 它的订单有这种行（那些采购行的采购员是别人，只出物料名和日期）。晚得多的在前。
     * 成品采购单是工厂包工包料，不等我们的料，不列（走查 T-120e64：同一张订单另有外发加工单时 CP 也被报了）
     */
    private TodoGroup cutRisk(Long owner) {
        List<Ranked> items = new ArrayList<>();
        long total = 0;
        if (can(PURCHASE_LIST)) {
            PendingArrivalQuery q = new PendingArrivalQuery();
            q.setTab("CUT");
            q.setBuyerId(owner);
            q.setSize(GROUP_SIZE);
            PageResult<PendingArrivalRowVO> page = purchaseService.pendingItems(q);
            total += page.getTotal();
            Map<String, String> units = dictDataService.labels("unit");
            Map<Long, DocRef> refs = refs(BizType.PURCHASE_ORDER, page.getList(), PendingArrivalRowVO::getPurchaseOrderId);
            for (PendingArrivalRowVO r : page.getList()) {
                String sub = BoardTexts.dot(r.getPurchaseNo(), "预计 " + BoardTexts.md(r.getArriveDate()) + " 到，开裁 "
                        + BoardTexts.md(r.getNeedDate()));
                items.add(new Ranked(new TodoItem(refs.get(r.getPurchaseOrderId()), shortOf(r, units), sub, r.getImage()),
                        -r.getCutLateDays()));
            }
        }
        if (can(PRODUCTION_LIST)) {
            Map<String, OrderCutRisk> risks = purchaseService.orderCutRisks();
            if (!risks.isEmpty()) {
                ProductionQuery q = activeProduction("OUTSOURCE");
                q.setMerchId(owner);
                q.setOrderNos(risks.keySet());
                q.setSize(GROUP_SIZE);
                PageResult<ProductionRowVO> page = productionService.page(q);
                List<ProductionRowVO> rows = new ArrayList<>(page.getList());
                total += page.getTotal();
                Map<Long, DocRef> refs = refs(BizType.PRODUCTION_ORDER, rows, ProductionRowVO::getId);
                for (ProductionRowVO p : rows) {
                    OrderCutRisk risk = risks.get(p.getOrderNo());
                    String sub = risk.materialName() + " 预计 " + BoardTexts.md(risk.arriveDate()) + " 到，开裁 " + BoardTexts.md(risk.needDate());
                    items.add(new Ranked(new TodoItem(refs.get(p.getId()), p.getNo() + " " + p.getStyleName(), sub, p.getImage()),
                            -risk.cutLateDays()));
                }
            }
        }
        return group("CUT_RISK", total, items);
    }

    /** 到料逾期：过了交期（有预计到货看预计到货）还没到齐的采购行，采购员筛；逾期久的在前。"其他提醒"的到料两个数也在这里 */
    private TodoGroup arrivalLate(Long owner, Map<String, Long> counts) {
        if (!can(PURCHASE_LIST)) {
            return null;
        }
        PendingArrivalQuery q = new PendingArrivalQuery();
        q.setBuyerId(owner);
        PendingArrivalCounts c = purchaseService.pendingCounts(q);
        counts.put("ARRIVAL_LATE", c.late());
        counts.put("ARRIVAL_CUT", c.cut());
        q.setTab("LATE");
        q.setSize(GROUP_SIZE);
        PageResult<PendingArrivalRowVO> page = purchaseService.pendingItems(q);
        Map<String, String> units = dictDataService.labels("unit");
        Map<Long, DocRef> refs = refs(BizType.PURCHASE_ORDER, page.getList(), PendingArrivalRowVO::getPurchaseOrderId);
        List<Ranked> items = new ArrayList<>();
        for (PendingArrivalRowVO r : page.getList()) {
            String sub = BoardTexts.dot(r.getPurchaseNo(), r.getSupplierName(), "应到 " + BoardTexts.md(r.getArriveDate()));
            items.add(new Ranked(new TodoItem(refs.get(r.getPurchaseOrderId()), shortOf(r, units), sub, r.getImage()),
                    r.getArriveDate().toEpochDay()));
        }
        return group("ARRIVAL_LATE", page.getTotal(), items);
    }

    /** 上游已变更：旧的在前（/upstream 页是新的在前） */
    private TodoGroup upstream(Long owner, Map<String, Long> counts) {
        List<UpstreamRows.Row> rows = upstreamRows.rows(null, owner);
        counts.put("UPSTREAM", (long) rows.size());
        List<Ranked> items = rows.stream().map(r -> new Ranked(new TodoItem(r.doc(), r.doc().typeLabel() + " " + r.doc().no(),
                r.notices().getFirst().content(), r.image()), epoch(r.latest()))).toList();
        return group("UPSTREAM", rows.size(), items);
    }

    /** 待排单：已审核、一件都没排的需求订单，跟单员筛；要能排单（production:order:add）。交期早的在前 */
    private TodoGroup unscheduled(Long owner, Map<String, Long> counts) {
        if (!can(ORDER_LIST) || !can(PRODUCTION_ADD)) {
            return null;
        }
        OrderQuery q = new OrderQuery();
        q.setUnscheduled(true);
        q.setMerchId(owner);
        q.setDueFirst(true);
        q.setSize(GROUP_SIZE);
        PageResult<OrderRowVO> page = orderService.page(q);
        counts.put("UNSCHEDULED", page.getTotal());
        Map<Long, DocRef> refs = refs(BizType.ORDER, page.getList(), OrderRowVO::getId);
        List<Ranked> items = new ArrayList<>();
        for (OrderRowVO o : page.getList()) {
            String sub = BoardTexts.dot(BoardTexts.count(o.getTotalQty() == null ? 0 : o.getTotalQty()) + " 件",
                    "交期 " + BoardTexts.md(due(o)));
            items.add(new Ranked(new TodoItem(refs.get(o.getId()), "需求订单 " + o.getNo(), sub, firstImage(o)), items.size()));
        }
        return group("UNSCHEDULED", page.getTotal(), items);
    }

    /** 异常待处理：没关闭的异常，生产单的跟单员筛；旧的在前 */
    private TodoGroup issues(Long owner) {
        if (!can(PRODUCTION_LIST)) {
            return null;
        }
        PageResult<OpenIssueVO> page = issueService.openIssues(owner, first(GROUP_SIZE));
        Map<Long, DocRef> refs = refs(BizType.PRODUCTION_ISSUE, page.getList(), OpenIssueVO::getId);
        Map<Long, AttachmentVO> images = styleImages(page.getList().stream().map(OpenIssueVO::getStyleId).toList());
        List<Ranked> items = new ArrayList<>();
        for (OpenIssueVO i : page.getList()) {
            String title = i.getIssueTypeName() + (i.getDescription() == null || i.getDescription().isBlank() ? "" : "：" + i.getDescription());
            String sub = BoardTexts.dot(i.getPrdOrderNo(), (i.getCreateByName() == null ? "" : i.getCreateByName() + " ")
                    + "上报于 " + BoardTexts.md(i.getCreateTime()));
            items.add(new Ranked(new TodoItem(refs.get(i.getId()), title, sub, images.get(i.getStyleId())), items.size()));
        }
        return group("ISSUE", page.getTotal(), items);
    }

    /** 证照将到期：30 天内到期（过期的也算）的供应商，负责采购员筛；到期早的在前 */
    private TodoGroup certs(Long owner, Map<String, Long> counts) {
        if (!can(SUPPLIER_LIST)) {
            return null;
        }
        SupplierQuery q = new SupplierQuery();
        q.setCertExpiring(true);
        q.setBuyerId(owner);
        q.setSize(GROUP_SIZE);
        PageResult<SupplierRowVO> page = supplierService.page(q);
        counts.put("CERT_EXPIRING", page.getTotal());
        LocalDate today = LocalDate.now(clock);
        List<Ranked> items = new ArrayList<>();
        for (SupplierRowVO s : page.getList()) {
            LocalDate until = s.getCertEarliestUntil();
            long days = until == null ? 0 : ChronoUnit.DAYS.between(today, until);
            String when = until == null ? null : days < 0 ? "最早一张 " + BoardTexts.md(until) + " 已过期"
                    : "最早一张 " + BoardTexts.md(until) + " 到期，还剩 " + days + " 天";
            items.add(new Ranked(new TodoItem(supplierRef(s), supplierName(s), BoardTexts.dot(when, "负责人 " + s.getBuyerName()), null),
                    until == null ? Long.MAX_VALUE : until.toEpochDay()));
        }
        return group("CERT", page.getTotal(), items);
    }

    // ---------------------------------------------------------------- 上游已变更

    @Override
    public UpstreamList upstream(UpstreamQuery query) {
        boolean canSeeAll = canSeeAll();
        Long owner = owner(canSeeAll, query.isMine(), query.getOwnerId());
        return new UpstreamList(canSeeAll, upstreamRows.rows(query.getType(), owner).stream()
                .map(r -> new UpstreamRow(r.doc(), r.notices())).toList());
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean can(String authority) {
        return LoginUser.hasAuthority(authority);
    }

    /** 真的"全部"数据范围（data:all），不看审核权限（06 §2.1） */
    private static boolean canSeeAll() {
        return DataScope.current(false).isAll();
    }

    /** 按哪个负责人筛：全部范围的人照他选的（null 是不筛），别的人只看自己的 */
    static Long owner(boolean canSeeAll, boolean mine, Long ownerId) {
        Long me = LoginUser.currentUserId();
        if (!canSeeAll) {
            return me;
        }
        return mine ? me : ownerId;
    }

    private static long put(Map<String, Long> counts, String key, long n) {
        counts.put(key, n);
        return n;
    }

    private static PageQuery first(int size) {
        PageQuery page = new PageQuery();
        page.setSize(size);
        return page;
    }

    private static ProductionQuery activeProduction(String kind) {
        ProductionQuery q = new ProductionQuery();
        q.setKind(kind);
        q.setStatus(List.of(DocStatus.APPROVED, DocStatus.RUNNING));
        return q;
    }

    private <T> Map<Long, DocRef> refs(BizType type, List<T> rows, Function<T, Long> id) {
        return rows.isEmpty() ? Map.of() : referenceService.describe(type, rows.stream().map(id).distinct().toList());
    }

    private Map<Long, AttachmentVO> styleImages(List<Long> styleIds) {
        return styleIds.isEmpty() ? Map.of() : attachmentService.firsts(BizType.STYLE, styleIds.stream().distinct().toList(), Attachment.IMAGE);
    }

    private static Ranked ranked(DocRef ref, String title, String sub, AttachmentVO image, OffsetDateTime time) {
        return new Ranked(new TodoItem(ref, title, sub, image), time == null ? Long.MAX_VALUE : epoch(time));
    }

    private static long epoch(OffsetDateTime time) {
        return time.toEpochSecond();
    }

    private static LocalDate due(OrderRowVO o) {
        return o.getCommitDate() != null ? o.getCommitDate() : o.getRequiredDate();
    }

    private static LocalDate due(ProductionRowVO p) {
        return p.getExpectDate() != null ? p.getExpectDate() : p.getDueDate();
    }

    private static AttachmentVO firstImage(OrderRowVO o) {
        return o.getStyles().stream().map(OrderStyleRef::image).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** 节点延期的那个节点：进度条上第一个 LATE 的，没有用当前节点 */
    private static String lateNode(ProductionRowVO p) {
        return p.getNodes().stream().filter(n -> "LATE".equals(n.state())).map(NodeDot::nodeName).findFirst()
                .orElse(p.getCurrentNodeName());
    }

    /** "卡其布 还差 120 米"（采购单位） */
    private static String shortOf(PendingArrivalRowVO r, Map<String, String> units) {
        return r.getMaterialName() + " 还差 " + BoardTexts.qty(r.getPendingQty()) + " "
                + units.getOrDefault(r.getPurchaseUnit(), r.getPurchaseUnit() == null ? "" : r.getPurchaseUnit());
    }

    private static String supplierName(SupplierRowVO s) {
        return s.getShortName() != null && !s.getShortName().isBlank() ? s.getShortName() : s.getName();
    }

    private String categories(SupplierRowVO s) {
        if (s.getCategories() == null || s.getCategories().isEmpty()) {
            return null;
        }
        Map<String, String> labels = dictDataService.labels("supplier_category");
        return s.getCategories().stream().map(c -> labels.getOrDefault(c, c)).collect(Collectors.joining("、"));
    }

    private static DocRef supplierRef(SupplierRowVO s) {
        StatusLabels.Label label = SUPPLIER_STATUS.label(s.getStatus());
        return new DocRef(BizType.SUPPLIER, null, null, BizType.SUPPLIER.getLabel(), s.getId(), s.getCode(), s.getStatus().name(),
                label.text(), label.tone(), s.getBuyerId(), s.getBuyerName());
    }
}
