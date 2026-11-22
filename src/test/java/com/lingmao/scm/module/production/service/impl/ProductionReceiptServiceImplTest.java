package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ReceiptOverRow;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回货（docs/api/05 §3.6，PRD §6.7.4） */
@ExtendWith(MockitoExtension.class)
class ProductionReceiptServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @Mock
    private ProductionReceiptMapper receiptMapper;
    @Mock
    private ProductionReceiptItemMapper receiptItemMapper;
    @Mock
    private ProductionOrderItemMapper itemMapper;
    @Mock
    private ProductionSupport support;
    @Mock
    private OrderService orderService;
    @Mock
    private StyleService styleService;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private UserService userService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private ProductionReceiptServiceImpl service() {
        return new ProductionReceiptServiceImpl(receiptMapper, receiptItemMapper, itemMapper, support, orderService, styleService,
                warehouseService, attachmentService, referenceService, userService, serialNoGenerator,
                org.mockito.Mockito.mock(com.lingmao.scm.module.biz.service.ActionRequestService.class), Clock.systemDefaultZone());
    }

    /** 规则（Q09 甲方 11-18 答 5%）：一个 SKU 累计回货 ≤ ⌊件数 × 1.05⌋，超了 1004，data 说清已回多少、还能回多少；不建单、不回写 */
    @Test
    void receive_overLimit_returnsRowsAndWritesNothing() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(100, 100)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().receiveNew(3L, req(6, 0)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(1004);
                    @SuppressWarnings("unchecked")
                    List<ReceiptOverRow> rows = (List<ReceiptOverRow>) ((BizException) e).getData();
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(r.maxQty()).isEqualTo(105);
                        assertThat(r.message()).isEqualTo("加工 100 件，已回良品 100，良品最多还能回 5 件");
                    });
                });
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
        verify(itemMapper, never()).addReturned(anyLong(), anyInt(), anyInt(), anyInt());
        verify(orderService, never()).addReturned(any());
    }

    /**
     * 规则（甲方 11-19"只计算良品"）：上限只看良品。件数 100、已回 100 其中次品 10（良品 90），这次回 18 件、次品 3：
     * 良品到 105，不超 ⌊100 × 1.05⌋；生产单明细回写回货 18、次品 3，订单只加良品 15
     */
    @Test
    void receive_limitCountsGoodOnly_andOrderGetsGood() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        ProductionOrderItem line = line(100, 100);
        line.setDefectQty(10);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));
        when(serialNoGenerator.daily(any())).thenReturn("HH202611170001");
        when(receiptMapper.insert(any(ProductionReceipt.class))).thenAnswer(inv -> {
            inv.<ProductionReceipt>getArgument(0).setId(77L);
            return 1;
        });
        when(itemMapper.addReturned(31L, 18, 3, 105)).thenReturn(1);
        when(support.refreshStatus(any(), eq("receive"), any())).thenReturn(DocStatus.DONE);

        assertThat(service().receiveNew(3L, req(18, 3)).status()).isEqualTo("DONE");

        verify(itemMapper).addReturned(31L, 18, 3, 105);
        verify(orderService).addReturned(Map.of(11L, 15));
    }

    /** 规则（§3.6.2）：次品算在回货数里，不能比回货多（400） */
    @Test
    void receive_defectMoreThanQty_400() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(100, 0)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().receiveNew(3L, req(5, 6)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
    }

    @Test
    void receive_existingAndRequestedCannotOverflowInt() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(Integer.MAX_VALUE, Integer.MAX_VALUE - 2)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));
        assertThatThrownBy(() -> service().receiveNew(3L, req(3, 0))).isInstanceOf(BizException.class)
                .hasMessageContaining("累计回货").extracting("code").isEqualTo(400);
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
        verify(itemMapper, never()).addReturned(anyLong(), anyInt(), anyInt(), anyInt());
        verify(orderService, never()).addReturned(any());
    }

    @Test
    void receive_totalAcrossSkusCannotOverflowInt() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        ProductionOrderItem second = line(1_500_000_000, 0);
        second.setId(32L); second.setOrderItemId(12L); second.setStyleSkuId(102L);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(1_500_000_000, 0), second));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));
        ReceiptReq request = req(1_500_000_000, 0);
        ReceiptReq.Item other = new ReceiptReq.Item();
        other.setPrdOrderItemId(32L); other.setQty(1_500_000_000); other.setDefectQty(0);
        request.setItems(List.of(request.getItems().getFirst(), other));
        assertThatThrownBy(() -> service().receiveNew(3L, request)).isInstanceOf(BizException.class)
                .hasMessageContaining("本次回货").extracting("code").isEqualTo(400);
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
        verify(itemMapper, never()).addReturned(anyLong(), anyInt(), anyInt(), anyInt());
        verify(orderService, never()).addReturned(any());
    }

    /** 规则（RFC 013 §4）：登记了裁床数，回货上限按 ⌊裁床数 × 1.05⌋：件数 100、裁床 90，最多回 94 */
    @Test
    void receive_withCutQty_limitByCut() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        ProductionOrderItem line = line(100, 90);
        line.setCutQty(90);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().receiveNew(3L, req(5, 0)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    List<ReceiptOverRow> rows = (List<ReceiptOverRow>) ((BizException) e).getData();
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(r.maxQty()).isEqualTo(94);
                        assertThat(r.message()).isEqualTo("裁床 90 件，已回良品 90，良品最多还能回 4 件");
                    });
                });
        verify(itemMapper, never()).addReturned(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 规则（RFC 013 §4）：裁床数不能超过 ⌊件数 × 1.05⌋，也不能让已回的超出新上限（已回 100 → 最少 96）；
     * 合规的写进去、记一条操作记录、按新的基数重算状态
     */
    @Test
    void registerCut_limits_thenUpdatesAndRefreshes() {
        lockedAs(DocStatus.RUNNING);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(200, 100)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().registerCut(3L, cut(211))).isInstanceOf(BizException.class)
                .hasMessageContaining("裁床数最多 210").extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service().registerCut(3L, cut(95))).isInstanceOf(BizException.class)
                .hasMessageContaining("裁床数不能少于 96").extracting("code").isEqualTo(400);
        verify(itemMapper, never()).updateCut(anyLong(), any());

        when(support.refreshStatus(any(), eq("cut"), any())).thenReturn(DocStatus.DONE);
        service().registerCut(3L, cut(102));
        verify(itemMapper).updateCut(31L, 102);
        verify(support).refreshStatus(any(), eq("cut"), eq("登记裁床数：A01-RED-M 未登记 → 102"));
        verify(support).syncOrder(eq(5L), any());
    }

    /** 规则（§2.1）：已结案的单不能登记回货（1000）；要登记先取消结案 */
    @Test
    void receive_closed_1000() {
        lockedAs(DocStatus.CLOSED);

        assertThatThrownBy(() -> service().receiveNew(3L, req(5, 0)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(1000);
                    assertThat(e.getMessage()).contains("已结案", "不能登记回货");
                });
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
    }

    private void lockedAs(DocStatus status) {
        ProductionOrder p = new ProductionOrder();
        p.setId(3L);
        p.setNo("JG202611170001");
        p.setKind(ProductionKind.OUTSOURCE);
        p.setStatus(status);
        p.setStyleId(1L);
        p.setOrderId(5L);
        when(support.lockWithOrder(3L)).thenReturn(new ProductionSupport.Locked(
                new OrderForProduction(5L, "DD202611170001", DocStatus.RUNNING, 1L, TODAY, TODAY, 2L, List.of()), p));
    }

    private static ProductionOrderItem line(int qty, int returned) {
        ProductionOrderItem i = new ProductionOrderItem();
        i.setId(31L);
        i.setPrdOrderId(3L);
        i.setOrderItemId(11L);
        i.setStyleSkuId(101L);
        i.setQty(qty);
        i.setReturnedQty(returned);
        i.setDefectQty(0);
        return i;
    }

    private static StyleOrderInfo style() {
        return new StyleOrderInfo(1L, "A01", "衬衫", null, null, null, null, List.of("M"),
                List.of(new StyleOrderInfo.Color(21L, "RED", "红", "#c00", true)),
                List.of(new StyleOrderInfo.Sku(101L, 21L, "M", "A01-RED-M", true)), null, null);
    }

    private static com.lingmao.scm.module.production.dto.CutQtyReq cut(Integer qty) {
        var i = new com.lingmao.scm.module.production.dto.CutQtyReq.Item();
        i.setPrdOrderItemId(31L);
        i.setCutQty(qty);
        var r = new com.lingmao.scm.module.production.dto.CutQtyReq();
        r.setItems(List.of(i));
        return r;
    }

    private static ReceiptReq req(int qty, int defect) {
        ReceiptReq.Item i = new ReceiptReq.Item();
        i.setPrdOrderItemId(31L);
        i.setQty(qty);
        i.setDefectQty(defect);
        ReceiptReq r = new ReceiptReq();
        r.setClientActionId(java.util.UUID.randomUUID());
        r.setReceiptDate(TODAY);
        r.setItems(List.of(i));
        return r;
    }
}
