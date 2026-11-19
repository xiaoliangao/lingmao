package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.fg.dto.FgShortRow;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.entity.FgStock;
import com.lingmao.scm.module.fg.entity.FgStockLog;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 成品过账引擎（RFC 008 §3.6）：合并、排序、条件更新、收集缺货、日志一个 SKU 一条 */
@ExtendWith(MockitoExtension.class)
class FgLedgerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 19);

    @Mock
    private FgStockMapper stockMapper;
    @Mock
    private FgStockLogMapper logMapper;
    @Mock
    private FgStyles styles;
    @Mock
    private WarehouseService warehouseService;

    private FgLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new FgLedger(stockMapper, logMapper, styles, warehouseService);
    }

    /** 规则：同仓同 SKU 的几笔合成一次更新；几个 SKU 按（仓库、SKU）从小到大改（两张单同时过账不死锁）；日志的结存是改完的数 */
    @Test
    void post_mergesSortsAndLogsBalance() {
        when(stockMapper.add(1L, 10L, 2)).thenReturn(12);
        when(stockMapper.add(1L, 20L, 7)).thenReturn(7);

        ledger.post(new FgLedger.Posting(5L, "CR202611190001", FgDocType.OTHER_IN, DAY, List.of(
                new FgLedger.Move(1L, 20L, 3, 1), new FgLedger.Move(1L, 10L, 2, 2), new FgLedger.Move(1L, 20L, 4, 3))));

        InOrder order = inOrder(stockMapper);
        order.verify(stockMapper).add(1L, 10L, 2);
        order.verify(stockMapper).add(1L, 20L, 7);
        ArgumentCaptor<FgStockLog> logs = ArgumentCaptor.forClass(FgStockLog.class);
        verify(logMapper, times(2)).insert(logs.capture());
        assertThat(logs.getAllValues()).extracting(FgStockLog::getStyleSkuId, FgStockLog::getChangeQty, FgStockLog::getBalanceQty,
                        FgStockLog::getChangeType, FgStockLog::getDocNo)
                .containsExactly(tuple(10L, 2, 12, FgDocType.OTHER_IN, "CR202611190001"),
                        tuple(20L, 7, 7, FgDocType.OTHER_IN, "CR202611190001"));
    }

    /** 规则：不够的不马上停，整张单试完一起报 1001（清单里有库存、要出、差多少），一条日志都不写 */
    @Test
    void post_collectsAllShortagesBeforeFailing() {
        when(stockMapper.deduct(1L, 10L, -5)).thenReturn(null);
        when(stockMapper.deduct(1L, 20L, -1)).thenReturn(3);
        when(stockMapper.deduct(1L, 30L, -9)).thenReturn(null);
        FgStock s10 = new FgStock();
        s10.setStyleSkuId(10L);
        s10.setQty(2);
        when(stockMapper.selectBySkus(1L, List.of(10L))).thenReturn(List.of(s10));
        when(stockMapper.selectBySkus(1L, List.of(30L))).thenReturn(List.of());
        when(styles.bySku(anyList())).thenReturn(Map.of());
        when(warehouseService.names(any())).thenReturn(Map.of(1L, "成品仓"));

        FgLedger.Posting out = new FgLedger.Posting(6L, "CC202611190001", FgDocType.SHIP_OUT, DAY, List.of(
                new FgLedger.Move(1L, 10L, -5, 1), new FgLedger.Move(1L, 20L, -1, 2), new FgLedger.Move(1L, 30L, -9, 3)));

        assertThatThrownBy(() -> ledger.post(out))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.STOCK_SHORTAGE);
                    assertThat(e.getMessage()).isEqualTo("库存不够：SKU 10 库存 2，差 3 件，等 2 个");
                    @SuppressWarnings("unchecked")
                    List<FgShortRow> rows = (List<FgShortRow>) e.getData();
                    assertThat(rows).extracting(FgShortRow::seqs, FgShortRow::warehouseName, FgShortRow::stockQty, FgShortRow::needQty,
                                    FgShortRow::shortQty)
                            .containsExactly(tuple("1", "成品仓", 2, 5, 3), tuple("3", "成品仓", 0, 9, 9));
                });
        verify(stockMapper).deduct(1L, 30L, -9);
        verify(logMapper, never()).insert(any(FgStockLog.class));
    }
}
