package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.inventory.dto.StockShortRow;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.entity.StockLog;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 过账引擎（架构 §5.2）：合并、排序、条件更新、收集缺料、日志一笔一条 */
@ExtendWith(MockitoExtension.class)
class StockLedgerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);

    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockLogMapper stockLogMapper;
    @Mock
    private MaterialService materialService;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private DictDataService dictDataService;

    private StockLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new StockLedger(stockMapper, stockLogMapper, materialService, warehouseService, dictDataService);
    }

    private static StockLedger.Move out(long warehouse, long sku, String qty, int seq, String lot) {
        return new StockLedger.Move(warehouse, sku, new BigDecimal(qty).negate(), StockChangeType.OTHER_OUT, seq, 100L + seq, null, lot);
    }

    /**
     * 规则：同仓同 SKU 的两行（缸号不同）合成一次条件更新，日志还是两条、各带各的缸号，结存按这一笔之后算；
     * 几个 SKU 按（SKU、仓库）排好序再改（防死锁；当前货主/库位固定 OWN/0/0）
     */
    @Test
    void post_mergesSameSkuButLogsEachLine() {
        when(stockMapper.deduct(eq(1L), eq(20L), eq(Stock.OWN), eq(0L), eq(0L), any())).thenReturn(new BigDecimal("4"));
        when(stockMapper.deduct(eq(1L), eq(10L), eq(Stock.OWN), eq(0L), eq(0L), any())).thenReturn(new BigDecimal("7"));

        ledger.post(new StockLedger.Posting(5L, null, "CK202611170001", DAY,
                List.of(out(1, 20, "2", 1, "A01"), out(1, 10, "1", 2, null), out(1, 20, "3", 3, "A02"))));

        InOrder order = inOrder(stockMapper);
        order.verify(stockMapper).deduct(1L, 10L, Stock.OWN, 0L, 0L, new BigDecimal("-1"));
        order.verify(stockMapper).deduct(1L, 20L, Stock.OWN, 0L, 0L, new BigDecimal("-5"));
        ArgumentCaptor<StockLog> logs = ArgumentCaptor.forClass(StockLog.class);
        verify(stockLogMapper, times(3)).insert(logs.capture());
        assertThat(logs.getAllValues()).extracting(StockLog::getMaterialSkuId, StockLog::getChangeQty, StockLog::getBalanceQty, StockLog::getDyeLot)
                .containsExactly(
                        tuple(10L, new BigDecimal("-1"), new BigDecimal("7"), null),
                        tuple(20L, new BigDecimal("-2"), new BigDecimal("7"), "A01"),
                        tuple(20L, new BigDecimal("-3"), new BigDecimal("4"), "A02"));
        assertThat(logs.getAllValues()).allSatisfy(l -> {
            assertThat(l.getInvDocId()).isEqualTo(5L);
            assertThat(l.getDocNo()).isEqualTo("CK202611170001");
            assertThat(l.getBizDate()).isEqualTo(DAY);
        });
    }

    /** 规则（PRD §6.6.6 验收 5）：不够的不马上停，整张单都试一遍，缺的全收集起来一起报 1001；一条日志都不写 */
    @Test
    void post_collectsAllShortagesThenThrows() {
        when(stockMapper.deduct(anyLong(), anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(null);
        Stock some = new Stock();
        some.setMaterialSkuId(10L);
        some.setQty(new BigDecimal("0.14"));
        when(stockMapper.selectBySkus(1L, List.of(10L))).thenReturn(List.of(some));
        when(stockMapper.selectBySkus(1L, List.of(20L))).thenReturn(List.of());
        MaterialSkuBrief a = brief(10L, "ML1-KQ", "斜纹棉", "卡其");
        MaterialSkuBrief b = brief(20L, "ML2-HS", "罗纹", "黑色");
        when(materialService.skuBriefs(any())).thenReturn(Map.of(10L, a, 20L, b));
        WarehouseVO w = new WarehouseVO();
        w.setName("面料仓");
        when(warehouseService.getById(1L)).thenReturn(w);
        when(dictDataService.listByType("unit")).thenReturn(List.of(new DictOption("米", "METER", 1)));

        assertThatThrownBy(() -> ledger.post(new StockLedger.Posting(5L, null, "CK1", DAY,
                List.of(out(1, 10, "1", 3, null), out(1, 20, "2", 1, null), out(1, 10, "0.5", 5, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.STOCK_SHORTAGE);
                    assertThat(e.getMessage()).isEqualTo("库存不够：斜纹棉-卡其 库存 0.14，差 1.36 米，等 2 种");
                    assertThat(e.getData()).asList().containsExactly(
                            new StockShortRow("3、5", "面料仓", "ML1-KQ", "斜纹棉-卡其", new BigDecimal("0.14"),
                                    new BigDecimal("1.5"), new BigDecimal("1.36"), "米"),
                            new StockShortRow("1", "面料仓", "ML2-HS", "罗纹-黑色", BigDecimal.ZERO,
                                    new BigDecimal("2"), new BigDecimal("2"), "米"));
                });
        verify(stockLogMapper, never()).insert(any(StockLog.class));
    }

    /** 规则：加的走"没有就建、有就加"；反审核把每笔反过来，日志类型不变 */
    @Test
    void post_reversedAddsBack() {
        when(stockMapper.add(1L, 10L, Stock.OWN, 0L, 0L, new BigDecimal("2"))).thenReturn(new BigDecimal("9"));
        StockLedger.Posting posting = new StockLedger.Posting(5L, null, "CK1", DAY, List.of(out(1, 10, "2", 1, null)));
        StockLog original = new StockLog();
        original.setId(900L);
        original.setWarehouseId(1L);
        original.setMaterialSkuId(10L);
        original.setInvDocItemId(101L);
        original.setChangeQty(new BigDecimal("-2"));
        original.setValuationStatus("RECORDED");
        original.setStockUnit("METER");
        original.setCurrency("CNY");
        original.setUnitPrice(new BigDecimal("2.0000"));
        original.setAmount(new BigDecimal("-4.00"));
        when(stockLogMapper.reversibleOf(5L, null)).thenReturn(List.of(original));

        ledger.post(posting.reversed());

        ArgumentCaptor<StockLog> log = ArgumentCaptor.forClass(StockLog.class);
        verify(stockLogMapper).insert(log.capture());
        assertThat(log.getValue().getChangeType()).isEqualTo(StockChangeType.OTHER_OUT);
        assertThat(log.getValue().getChangeQty()).isEqualByComparingTo("2");
        assertThat(log.getValue().getBalanceQty()).isEqualByComparingTo("9");
        assertThat(log.getValue().getReversalOfLogId()).isEqualTo(900L);
        assertThat(log.getValue().getUnitPrice()).isEqualByComparingTo("2");
        assertThat(log.getValue().getAmount()).isEqualByComparingTo("4");
    }

    private static MaterialSkuBrief brief(long id, String code, String name, String color) {
        MaterialSkuBrief b = new MaterialSkuBrief();
        b.setMaterialSkuId(id);
        b.setSkuCode(code);
        b.setMaterialName(name);
        b.setColorName(color);
        b.setStockUnit("METER");
        return b;
    }
}
