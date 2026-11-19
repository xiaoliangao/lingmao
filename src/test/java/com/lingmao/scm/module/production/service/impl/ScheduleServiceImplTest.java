package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.production.dto.ScheduleOverRow;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 排单（docs/api/02 §3.7，PRD §6.5.5） */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @Mock
    private ProductionOrderMapper prdMapper;
    @Mock
    private ProductionOrderItemMapper itemMapper;
    @Mock
    private ProductionSupport support;
    @Mock
    private OrderService orderService;
    @Mock
    private StyleService styleService;
    @Mock
    private TechPackService techPackService;
    @Mock
    private SupplierService supplierService;
    @Mock
    private PriceService priceService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private UserService userService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    @InjectMocks
    private ScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.lingmao.scm.security.LoginUser(1L, "unit-schedule"), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("production:price:query"))));
        // 订单行 11：100 件，已排 50；锁定的生产 BOM 是 9
        when(orderService.lockForProduction(5L)).thenReturn(new OrderForProduction(5L, "DD202611170001", DocStatus.APPROVED, 1L,
                LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 25), 3L,
                List.of(new OrderForProduction.Line(11L, 1L, 101L, 100, 50, 0, 9L))));
        when(styleService.orderInfos(Set.of(1L))).thenReturn(Map.of(1L, new StyleOrderInfo(1L, "A01", "衬衫", null, null, null, null,
                List.of("M"), List.of(new StyleOrderInfo.Color(21L, "RED", "红", "#c00", true)),
                List.of(new StyleOrderInfo.Sku(101L, 21L, "M", "A01-RED-M", true)), null, null)));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    /** 规则（PRD §6.5.5，Q09 甲方 11-18 答 5%）：同一个 SKU 已排产 + 这次 ≤ ⌊订单数 × 1.05⌋，超了 1004，data 说清最多还能排多少，一张都不建 */
    @Test
    void schedule_overLimit_returnsRowsAndCreatesNothing() {
        assertThatThrownBy(() -> service.schedule(req(56, new BigDecimal("38"), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(1004);
                    @SuppressWarnings("unchecked")
                    List<ScheduleOverRow> rows = (List<ScheduleOverRow>) ((BizException) e).getData();
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(r.sku()).isEqualTo("A01-RED-M");
                        assertThat(r.maxQty()).isEqualTo(105);
                        assertThat(r.message()).isEqualTo("最多还能排 55 件");
                    });
                });
        verify(prdMapper, never()).insert(any(com.lingmao.scm.module.production.entity.ProductionOrder.class));
        verify(orderService, never()).addScheduled(any());
    }

    @Test
    void schedule_sameSkuAcrossBlocksCannotOverflowInt() {
        when(orderService.lockForProduction(5L)).thenReturn(new OrderForProduction(5L, "DD202611170001", DocStatus.APPROVED, 1L,
                TODAY, TODAY, 3L, List.of(new OrderForProduction.Line(11L, 1L, 101L, Integer.MAX_VALUE, 0, 0, 9L))));
        ScheduleReq request = req(1_500_000_000, BigDecimal.ONE, null);
        request.setBlocks(List.of(request.getBlocks().getFirst(), request.getBlocks().getFirst()));
        assertThatThrownBy(() -> service.schedule(request)).isInstanceOf(BizException.class)
                .hasMessageContaining("第 2 块同一 SKU 累计排单").extracting("code").isEqualTo(400);
        verify(prdMapper, never()).insert(any(com.lingmao.scm.module.production.entity.ProductionOrder.class));
        verify(orderService, never()).addScheduled(any());
    }

    @Test
    void schedule_existingAndRequestedCannotOverflowInt() {
        when(orderService.lockForProduction(5L)).thenReturn(new OrderForProduction(5L, "DD202611170001", DocStatus.APPROVED, 1L,
                TODAY, TODAY, 3L, List.of(new OrderForProduction.Line(11L, 1L, 101L, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 0, 9L))));
        assertThatThrownBy(() -> service.schedule(req(2, BigDecimal.ONE, null))).isInstanceOf(BizException.class)
                .hasMessageContaining("累计已排").extracting("code").isEqualTo(400);
        verify(prdMapper, never()).insert(any(com.lingmao.scm.module.production.entity.ProductionOrder.class));
        verify(orderService, never()).addScheduled(any());
    }

    /**
     * 规则（02 §3.7）：一块一块查完再一起报 1000，data 是清单：加工厂停用、单价和档案现价不一样没写原因都列出来；一张都不建
     */
    @Test
    void schedule_collectsProblemsBeforeCreating() {
        when(support.today()).thenReturn(TODAY);
        when(supplierService.refs(Set.of(8L))).thenReturn(Map.of(8L, new SupplierRef(8L, "S08", "某某制衣", "某某", SupplierStatus.DISABLED,
                null, "MONTHLY", null, null, null, null, null)));
        when(supplierService.categories(Set.of(8L))).thenReturn(Map.of(8L, List.of("GARMENT")));
        when(dictDataService.listByType("process_mode")).thenReturn(List.of(new DictOption("清加工", "CMT", 1)));
        when(priceService.currentProcess(8L, 1L, "CMT", TODAY)).thenReturn(Optional.of(
                new PriceRef(66L, new BigDecimal("35.00"), "PIECE", null, true, true, TODAY.minusDays(30), null,
                        com.lingmao.scm.module.price.entity.PriceType.PROCESS)));
        when(userService.isEnabled(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.schedule(req(40, new BigDecimal("38"), " ")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(1000);
                    assertThat(((BizException) e).getData()).asInstanceOf(InstanceOfAssertFactories.list(String.class)).containsExactly(
                            "第 1 块（A01 / 某某）：加工厂某某现在是「停用」，不能下外发加工单",
                            "第 1 块（A01 / 某某）：单价和档案现价 35.00 不一样，要写改价原因");
                });
        verify(prdMapper, never()).insert(any(com.lingmao.scm.module.production.entity.ProductionOrder.class));
        verify(orderService, never()).addScheduled(any());
    }

    private static ScheduleReq req(int qty, BigDecimal price, String reason) {
        ScheduleReq.Item item = new ScheduleReq.Item();
        item.setOrderItemId(11L);
        item.setQty(qty);
        ScheduleReq.Block b = new ScheduleReq.Block();
        b.setStyleId(1L);
        b.setFactoryId(8L);
        b.setProcessMode("CMT");
        b.setUnitPrice(price);
        b.setPriceChangeReason(reason);
        b.setDueDate(LocalDate.of(2026, 12, 10));
        b.setMerchId(3L);
        b.setItems(List.of(item));
        ScheduleReq req = new ScheduleReq();
        req.setOrderId(5L);
        req.setBlocks(List.of(b));
        return req;
    }
}
