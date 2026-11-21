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
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private PurchaseOffsetBook offsetBook;

    private PurchaseStockHandler handler;

    /** 状态回写用 LambdaUpdateWrapper，要表信息 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
    }

    @BeforeEach
    void setUp() {
        handler = new PurchaseStockHandler(poMapper, itemMapper, materialService, dictDataService, docLogService, offsetBook);
        // 冲抵的取数、写回另有 OverOffsetPlanTest 和 PurchaseIT；这里只锁这一张、什么都不冲
        lenient().when(offsetBook.poIdsToLock(anyLong(), any(), anyBoolean())).thenReturn(new TreeSet<>(Set.of(5L)));
        lenient().when(poMapper.lockForUpdate(any())).thenReturn(List.of(5L));
        lenient().when(offsetBook.settle(any(), any(), any())).thenReturn(PurchaseOffsetBook.Settled.NONE);
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
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("60", "0")), List.of(item("105", "0")));
        when(poMapper.update(any(), any())).thenReturn(1);

        handler.onPosted(posting(InvDocType.PURCHASE_IN, "45"));

        verify(itemMapper).addPosted(eq(11L), eq(new BigDecimal("45")), eq(BigDecimal.ZERO));
        verify(offsetBook).settle(eq(Map.of(11L, new BigDecimal("45"))), eq(Set.of(5L)), any());
        verify(docLogService).add(eq(BizType.PURCHASE_ORDER), eq(5L), eq("status"), eq(DocStatus.RUNNING), eq(DocStatus.DONE), any());
    }

    /**
     * 别的单多收冲来 5，自己入了 95 就到齐了（RFC 014）：已到齐还能入库，供应商照单再送的 5 入得进来（上限还是按净入库 105 − 95）；
     * 自己入够了采购数的已到齐单照旧不能入
     */
    @Test
    void check_doneByOffset_canStillReceive() {
        PurchaseOrderItem covered = item("95", "0");
        covered.setOffsetInQty(new BigDecimal("5"));
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.DONE));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(covered), List.of(item("100", "0")));

        handler.check(posting(InvDocType.PURCHASE_IN, "10"));

        assertThatThrownBy(() -> handler.check(posting(InvDocType.PURCHASE_IN, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("不能入库"));
    }

    /** 已到齐的两行单：第 1 行靠冲入到齐能再入；第 2 行自己入够了，哪怕还有溢收余量也不能再入（RFC 014 复查 #2） */
    @Test
    void check_doneByOffset_onlyCoveredLinesCanReceive() {
        PurchaseOrderItem covered = item("95", "0");
        covered.setOffsetInQty(new BigDecimal("5"));
        PurchaseOrderItem full = item("100", "0");
        full.setId(12L);
        full.setSeq(2);
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.DONE));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(covered, full));
        SourcePosting second = new SourcePosting(1L, "RK202611170001", InvDocType.PURCHASE_IN, 5L,
                List.of(new SourcePosting.Line(1, 12L, 100L, new BigDecimal("3"), new BigDecimal("3"))));

        handler.check(posting(InvDocType.PURCHASE_IN, "5"));
        assertThatThrownBy(() -> handler.check(second))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("第 2 行自己已经入够了"));
    }

    /** 只靠冲入变成部分到货、自己没入过库的单不能退货（复查 #7） */
    @Test
    void check_offsetOnly_cannotReturn() {
        PurchaseOrderItem onlyOffset = item("0", "0");
        onlyOffset.setOffsetInQty(new BigDecimal("50"));
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.RUNNING));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(onlyOffset));

        assertThatThrownBy(() -> handler.check(posting(InvDocType.PURCHASE_RETURN, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("不能退货"));
    }

    /** 同组别的单查的时候还在、锁之前被删了：锁不上的跳过；这张单自己没锁上才 404（复查 #4） */
    @Test
    void lockAll_missingOtherOrderIsSkipped_missingSelfIs404() {
        when(offsetBook.poIdsToLock(anyLong(), any(), anyBoolean())).thenReturn(new TreeSet<>(Set.of(5L, 9L)));
        when(poMapper.lockForUpdate(any())).thenReturn(List.of(5L), List.of(9L));
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.RUNNING));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("0", "0")), List.of(item("10", "0")));

        handler.onPosted(posting(InvDocType.PURCHASE_IN, "10"));
        verify(offsetBook).settle(any(), eq(Set.of(5L)), any());

        assertThatThrownBy(() -> handler.onPosted(posting(InvDocType.PURCHASE_IN, "10")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(404));
    }

    /** 退货让净入库变少：冲抵按 −30 算（退回冲出去的在 OverOffsetPlan 里） */
    @Test
    void return_passesNegativeNetDeltaToOffsets() {
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.DONE));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("105", "0")), List.of(item("105", "0")), List.of(item("105", "30")));
        when(poMapper.update(any(), any())).thenReturn(1);

        handler.preparePosting(posting(InvDocType.PURCHASE_RETURN, "30"), false);

        verify(offsetBook).poIdsToLock(5L, Set.of(11L), false);
        verify(offsetBook).settle(eq(Map.of(11L, new BigDecimal("-30"))), eq(Set.of(5L)), any());
        verify(docLogService).add(eq(BizType.PURCHASE_ORDER), eq(5L), eq("status"), eq(DocStatus.DONE), eq(DocStatus.RUNNING), any());
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

    /** 采购100，入100→退50→补50后，反审退货会净收150，必须在写累计前拒绝。 */
    @Test
    void onUnposted_returnAfterReplacement_cannotBreakNetCap() {
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.DONE));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("150", "50")));

        assertThatThrownBy(() -> handler.preparePosting(posting(InvDocType.PURCHASE_RETURN, "50"), true))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT);
                    assertThat(e.getMessage()).contains("先撤销补收入库");
                    assertThat((List<?>) e.getData()).singleElement().satisfies(o ->
                            assertThat(((InvOverRow) o).maxQty()).isEqualByComparingTo("105"));
                });
        verify(itemMapper, never()).addPosted(anyLong(), any(), any());
    }

    @Test
    void onUnposted_returnAfterUndoingReplacement_isAllowed() {
        when(poMapper.lockById(5L)).thenReturn(po(DocStatus.RUNNING));
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("100", "50")), List.of(item("100", "0")));
        when(poMapper.update(any(), any())).thenReturn(1);

        handler.preparePosting(posting(InvDocType.PURCHASE_RETURN, "50"), true);
        verify(itemMapper).addPosted(11L, BigDecimal.ZERO, new BigDecimal("-50"));
        verify(offsetBook).poIdsToLock(5L, Set.of(11L), true);
        verify(offsetBook).settle(eq(Map.of(11L, new BigDecimal("50"))), eq(Set.of(5L)), any());
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
