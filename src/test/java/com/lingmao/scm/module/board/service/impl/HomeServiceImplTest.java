package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusTone;
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
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderTotals;
import com.lingmao.scm.module.order.service.OrderChangeService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.purchase.service.SupplyService;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 首页（docs/api/06）：哪一块出不出看模块权限；负责人筛选只给数据范围"全部"的人；待我审核不按负责人筛，按提交先后合起来排
 */
@ExtendWith(MockitoExtension.class)
class HomeServiceImplTest {

    private static final long ME = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Mock private StyleService styleService;
    @Mock private TechPackService techPackService;
    @Mock private OrderService orderService;
    @Mock private OrderChangeService changeService;
    @Mock private ProductionOrderService productionService;
    @Mock private ProductionBoardService productionBoardService;
    @Mock private ProductionIssueService issueService;
    @Mock private PurchaseOrderService purchaseService;
    @Mock private MrpService mrpService;
    @Mock private SupplyService supplyService;
    @Mock private InvDocService invDocService;
    @Mock private CountService countService;
    @Mock private SupplierService supplierService;
    @Mock private ReferenceService referenceService;
    @Mock private AttachmentService attachmentService;
    @Mock private DictDataService dictDataService;
    @Mock private UpstreamRows upstreamRows;

    private HomeServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"));
        service = new HomeServiceImpl(styleService, techPackService, orderService, changeService, productionService, productionBoardService,
                issueService, purchaseService, mrpService, supplyService, invDocService, countService, supplierService, referenceService,
                attachmentService, dictDataService, upstreamRows, clock);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：没有那个模块列表权限的格子是 null，也不去问那个模块；变更待审要 order:change:list，没有是 null */
    @Test
    void kpi_cellWithoutModulePermissionIsNull() {
        login("order:order:list");
        when(orderService.totals(any())).thenAnswer(inv -> {
            OrderQuery q = inv.getArgument(0);
            if (q.isUnscheduled()) {
                return new OrderTotals(2, 300);
            }
            return q.getStatus().contains(DocStatus.PENDING) ? new OrderTotals(3, 500) : new OrderTotals(5, 1200);
        });

        HomeKpi kpi = service.kpi();

        assertThat(kpi.dev()).isNull();
        assertThat(kpi.production()).isNull();
        assertThat(kpi.material()).isNull();
        assertThat(kpi.order()).isEqualTo(new HomeKpi.Order(1200, 5, 3, 2, null));
        verifyNoInteractions(styleService, changeService, productionBoardService, purchaseService, mrpService);
    }

    /**
     * 规则：待我审核用列表的 auditForMe（轮到我这一级），不按负责人筛；几种单据合起来按建单先后排，审核类的数和列表的 total 一样。
     * 普通的组按选的负责人筛（全部范围的人选了 ownerId = 9）
     */
    @Test
    void todos_auditIgnoresOwnerFilterAndMergesOldestFirst() {
        login(RoleDataScope.ALL_AUTHORITY, "order:order:list", "purchase:order:list");
        OffsetDateTime older = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.ofHours(8));
        when(orderService.page(any())).thenReturn(new PageResult<>(List.of(orderRow(1L, "DD2611180001", older.plusDays(5))), 1));
        when(purchaseService.page(any())).thenReturn(new PageResult<>(List.of(purchaseRow(2L, "CG2611100001", older)), 1));
        when(purchaseService.pendingItems(any())).thenReturn(new PageResult<>(List.of(), 0));
        when(purchaseService.pendingCounts(any())).thenReturn(new PendingArrivalCounts(0, 0, 0, 0));
        when(referenceService.describe(any(), anyCollection())).thenAnswer(inv -> refs(inv.getArgument(0), inv.getArgument(1)));
        HomeTodoQuery query = new HomeTodoQuery();
        query.setOwnerId(9L);

        HomeTodos todos = service.todos(query);

        assertThat(todos.canSeeAll()).isTrue();
        assertThat(todos.groups()).extracting(TodoGroup::key).containsExactly("AUDIT");
        TodoGroup audit = todos.groups().getFirst();
        assertThat(audit.total()).isEqualTo(2);
        assertThat(audit.items()).extracting(TodoItem::title).containsExactly("采购单 CG2611100001", "需求订单 DD2611180001");
        assertThat(todos.counts()).containsExactly(new TodoCount("ORDER_PENDING", 1), new TodoCount("PURCHASE_PENDING", 1));

        ArgumentCaptor<OrderQuery> orderQuery = ArgumentCaptor.forClass(OrderQuery.class);
        verify(orderService).page(orderQuery.capture());
        assertThat(orderQuery.getValue().isAuditForMe()).isTrue();
        assertThat(orderQuery.getValue().getMerchId()).isNull();
        ArgumentCaptor<PendingArrivalQuery> arrivals = ArgumentCaptor.forClass(PendingArrivalQuery.class);
        verify(purchaseService).pendingCounts(arrivals.capture());
        assertThat(arrivals.getValue().getBuyerId()).isEqualTo(9L);
        verify(upstreamRows).rows(null, 9L);
    }

    /** 规则：数据范围不是"全部"的人只看负责人是自己的，传别人的 ownerId 也一样；数是 0 的不给 */
    @Test
    void todos_selfScopeAlwaysFiltersToMe() {
        login("purchase:order:list");
        when(purchaseService.page(any())).thenReturn(new PageResult<>(List.of(), 0));
        when(purchaseService.pendingItems(any())).thenReturn(new PageResult<>(List.of(), 0));
        when(purchaseService.pendingCounts(any())).thenReturn(new PendingArrivalCounts(5, 2, 1, 0));
        HomeTodoQuery query = new HomeTodoQuery();
        query.setOwnerId(9L);

        HomeTodos todos = service.todos(query);

        assertThat(todos.canSeeAll()).isFalse();
        assertThat(todos.groups()).isEmpty();
        assertThat(todos.counts()).containsExactly(new TodoCount("ARRIVAL_LATE", 2));
        ArgumentCaptor<PendingArrivalQuery> arrivals = ArgumentCaptor.forClass(PendingArrivalQuery.class);
        verify(purchaseService).pendingCounts(arrivals.capture());
        assertThat(arrivals.getValue().getBuyerId()).isEqualTo(ME);
        verify(upstreamRows).rows(null, ME);
    }

    /** 规则：需求订单和两种生产单合起来，交期早的在前（逾期的在最前），最多 8 条；还有几天按东八区今天算 */
    @Test
    void deliveries_mergeByDueDateAndLimit() {
        login("order:order:list", "production:order:list");
        OrderRowVO order = orderRow(1L, "DD2611180001", null);
        order.setCommitDate(TODAY.plusDays(3));
        when(orderService.page(any())).thenReturn(new PageResult<>(List.of(order), 1));
        when(productionService.page(any())).thenAnswer(inv -> {
            ProductionQuery q = inv.getArgument(0);
            assertThat(q.isDueFirst()).isTrue();
            assertThat(q.getDueDateTo()).isEqualTo(TODAY.plusDays(7));
            if (!"OUTSOURCE".equals(q.getKind())) {
                return new PageResult<>(List.of(), 0);
            }
            List<ProductionRowVO> rows = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                rows.add(productionRow(10L + i, "JG26111800" + i, TODAY.minusDays(1).plusDays(i * 2L)));
            }
            return new PageResult<>(rows, 8);
        });
        when(referenceService.describe(any(), anyCollection())).thenAnswer(inv -> refs(inv.getArgument(0), inv.getArgument(1)));
        when(styleService.refs(any())).thenReturn(Map.of());

        List<DeliveryRow> rows = service.deliveries();

        assertThat(rows).hasSize(8);
        assertThat(rows.getFirst().ref().no()).isEqualTo("JG261118000");
        assertThat(rows.getFirst().daysLeft()).isEqualTo(-1);
        // 订单 +3 天排在生产单 +1 天之后、+3 天（同一天按单号）之前
        assertThat(rows).extracting(r -> r.ref().no()).containsSubsequence("JG261118001", "DD2611180001", "JG261118002");
        assertThat(rows).extracting(DeliveryRow::dueDate).isSorted();
    }

    // ---------------------------------------------------------------- 数据

    private static void login(String... authorities) {
        List<SimpleGrantedAuthority> granted = Stream.concat(Stream.of(authorities), Stream.of(LineScope.ALL_AUTHORITY))
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(ME, "jti"), null, granted));
    }

    private static Map<Long, DocRef> refs(BizType type, Collection<Long> ids) {
        return ids.stream().collect(Collectors.toMap(id -> id, id -> new DocRef(type, null, null, type.getLabel(), id,
                switch (type) {
                    case ORDER -> "DD2611180001";
                    case PURCHASE_ORDER -> "CG2611100001";
                    default -> "JG26111800" + (id - 10);
                }, "PENDING", "待审核", StatusTone.PENDING, null, null)));
    }

    private static OrderRowVO orderRow(long id, String no, OffsetDateTime createTime) {
        OrderRowVO o = new OrderRowVO();
        o.setId(id);
        o.setNo(no);
        o.setTotalQty(1200);
        o.setRequiredDate(TODAY.plusDays(20));
        o.setCreateTime(createTime);
        return o;
    }

    private static PurchaseRowVO purchaseRow(long id, String no, OffsetDateTime createTime) {
        PurchaseRowVO p = new PurchaseRowVO();
        p.setId(id);
        p.setNo(no);
        p.setSupplierName("示例面料");
        p.setItemCount(2);
        p.setDeliveryDate(TODAY.plusDays(5));
        p.setCreateTime(createTime);
        return p;
    }

    private static ProductionRowVO productionRow(long id, String no, LocalDate due) {
        ProductionRowVO p = new ProductionRowVO();
        p.setId(id);
        p.setNo(no);
        p.setStyleName("示例款");
        p.setFactoryShortName("示例制衣");
        p.setDueDate(due);
        p.setTotalQty(100);
        return p;
    }
}
