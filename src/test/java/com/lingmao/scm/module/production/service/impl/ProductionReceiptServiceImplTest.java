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
                warehouseService, attachmentService, referenceService, userService, serialNoGenerator, Clock.systemDefaultZone());
    }

    /** 规则（Q09）：一个 SKU 累计回货 ≤ ⌊件数 × 1.03⌋，超了 1004，data 说清已回多少、还能回多少；不建单、不回写 */
    @Test
    void receive_overLimit_returnsRowsAndWritesNothing() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(100, 100)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().receive(3L, req(5, 0)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(1004);
                    @SuppressWarnings("unchecked")
                    List<ReceiptOverRow> rows = (List<ReceiptOverRow>) ((BizException) e).getData();
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(r.maxQty()).isEqualTo(103);
                        assertThat(r.message()).isEqualTo("加工 100 件，已回 100，最多还能回 3 件");
                    });
                });
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
        verify(itemMapper, never()).addReturned(anyLong(), anyInt(), anyInt(), anyInt());
        verify(orderService, never()).addReturned(any());
    }

    /** 规则（§3.6.2）：次品算在回货数里，不能比回货多（400） */
    @Test
    void receive_defectMoreThanQty_400() {
        lockedAs(DocStatus.RUNNING);
        when(support.today()).thenReturn(TODAY);
        when(itemMapper.selectByOrder(3L)).thenReturn(List.of(line(100, 0)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style()));

        assertThatThrownBy(() -> service().receive(3L, req(5, 6)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        verify(receiptMapper, never()).insert(any(ProductionReceipt.class));
    }

    /** 规则（§2.1）：已结案的单不能登记回货（1000）；要登记先取消结案 */
    @Test
    void receive_closed_1000() {
        lockedAs(DocStatus.CLOSED);

        assertThatThrownBy(() -> service().receive(3L, req(5, 0)))
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

    private static ReceiptReq req(int qty, int defect) {
        ReceiptReq.Item i = new ReceiptReq.Item();
        i.setPrdOrderItemId(31L);
        i.setQty(qty);
        i.setDefectQty(defect);
        ReceiptReq r = new ReceiptReq();
        r.setReceiptDate(TODAY);
        r.setItems(List.of(i));
        return r;
    }
}
