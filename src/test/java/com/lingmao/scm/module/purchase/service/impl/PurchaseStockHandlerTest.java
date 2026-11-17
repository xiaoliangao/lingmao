package com.lingmao.scm.module.purchase.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.inventory.dto.InvOverRow;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 采购入库、退货回写采购单（docs/api/03 §3.3.9、Q09 溢收 5%） */
@ExtendWith(MockitoExtension.class)
class PurchaseStockHandlerTest {

    @Mock
    private PurchaseOrderMapper poMapper;
    @Mock
    private PurchaseOrderItemMapper itemMapper;
    @Mock
    private MaterialService materialService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private DocLogService docLogService;

    private PurchaseStockHandler handler;

    /** 状态回写用 LambdaUpdateWrapper，要表信息 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
    }

    @BeforeEach
    void setUp() {
        handler = new PurchaseStockHandler(poMapper, itemMapper, materialService, dictDataService, docLogService);
    }

    /** 采购 100、已入 60：这次最多 105 − 60 = 45，入 50 是 1004，清单里写上限 */
    @Test
    void check_overFivePercent_isOverLimit() {
        PurchaseOrder po = po(DocStatus.RUNNING);
        when(poMapper.lockById(5L)).thenReturn(po);
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("60", "0")));
        when(materialService.skuBriefs(any())).thenReturn(Map.of());
        when(dictDataService.listByType("unit")).thenReturn(List.of());

        assertThatThrownBy(() -> handler.check(posting(InvDocType.PURCHASE_IN, "50")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT);
                    assertThat((List<?>) e.getData()).singleElement().satisfies(o -> {
                        InvOverRow r = (InvOverRow) o;
                        assertThat(r.thisQty()).isEqualByComparingTo("50");
                        assertThat(r.maxQty()).isEqualByComparingTo("45");
                    });
                });
    }

    /** 过账回写：入到 105 ≥ 采购数，部分到货 → 已到齐，记一条状态变化 */
    @Test
    void onPosted_reachingQty_marksDone() {
        PurchaseOrder po = po(DocStatus.RUNNING);
        when(poMapper.lockById(5L)).thenReturn(po);
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("60", "0")));
        when(poMapper.update(any(), any())).thenReturn(1);

        handler.onPosted(posting(InvDocType.PURCHASE_IN, "45"));

        verify(itemMapper).addPosted(eq(11L), eq(new BigDecimal("45")), eq(BigDecimal.ZERO));
        verify(docLogService).add(eq(BizType.PURCHASE_ORDER), eq(5L), eq("status"), eq(DocStatus.RUNNING), eq(DocStatus.DONE), any());
    }

    /** 入了 100、退了 30：反审核 80 的入库单会让净入库变负，先反审核退货单（1000），什么都不改 */
    @Test
    void onUnposted_afterReturn_isBlocked() {
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.RUNNING));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("100", "30")));

        assertThatThrownBy(() -> handler.onUnposted(posting(InvDocType.PURCHASE_IN, "80")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("先反审核退货单");
                });
        verify(itemMapper, never()).addPosted(anyLong(), any(), any());
    }

    private static PurchaseOrder po(DocStatus status) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(5L);
        po.setNo("CG202611170001");
        po.setStatus(status);
        po.setVersion(3);
        return po;
    }

    private static PurchaseOrderItem item(String received, String returned) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setId(11L);
        i.setPurchaseOrderId(5L);
        i.setSeq(1);
        i.setMaterialSkuId(100L);
        i.setPurchaseUnit("METER");
        i.setConversionFactor(BigDecimal.ONE);
        i.setQty(new BigDecimal("100"));
        i.setReceivedQty(new BigDecimal(received));
        i.setReturnedQty(new BigDecimal(returned));
        return i;
    }

    private static SourcePosting posting(InvDocType type, String qty) {
        return new SourcePosting(1L, "RK202611170001", type, 5L,
                List.of(new SourcePosting.Line(1, 11L, 100L, new BigDecimal(qty), new BigDecimal(qty))));
    }
}
