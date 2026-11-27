package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.production.dto.LedgerStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 工厂料账的纯规则（RFC 015 §2、§10）：料账状态、超耗几种、该退几种、核销单带哪些料 */
class FactoryLedgerServiceImplTest {

    @Test
    void ledgerStatus_byProductionStatusAndSettleDoc() {
        assertThat(LedgerStatus.of(DocStatus.APPROVED, null)).isEqualTo(LedgerStatus.IN_PROGRESS);
        assertThat(LedgerStatus.of(DocStatus.RUNNING, null)).isEqualTo(LedgerStatus.IN_PROGRESS);
        assertThat(LedgerStatus.of(DocStatus.DONE, null)).isEqualTo(LedgerStatus.TO_SETTLE);
        assertThat(LedgerStatus.of(DocStatus.CLOSED, "DRAFT")).isEqualTo(LedgerStatus.SETTLING);
        assertThat(LedgerStatus.of(DocStatus.DONE, "APPROVED")).isEqualTo(LedgerStatus.SETTLED);
        assertThat(LedgerStatus.of(DocStatus.DRAFT, null)).isNull();
        assertThat(LedgerStatus.of(DocStatus.VOIDED, null)).isNull();
    }

    /** 例子（PRD §4.3）：领 990.86、应耗 981.178；实耗 985 → 超耗 1 种、结余 5.86 → 该退 1 种；没核销时用应耗估 */
    @Test
    void overAndReturnCounts() {
        Map<Long, BigDecimal> consume = Map.of(1L, new BigDecimal("981.178"), 2L, new BigDecimal("400"));
        Map<Long, BigDecimal> issued = Map.of(1L, new BigDecimal("990.86"), 2L, new BigDecimal("400"));
        assertThat(FactoryLedgerServiceImpl.returnCount(consume, issued, null)).isEqualTo(1);
        Map<Long, BigDecimal> settled = Map.of(1L, new BigDecimal("985"), 2L, new BigDecimal("400"));
        assertThat(FactoryLedgerServiceImpl.overCount(consume, settled)).isEqualTo(1);
        assertThat(FactoryLedgerServiceImpl.returnCount(consume, issued, settled)).isEqualTo(1);
        // 实耗把领的都用完：不用退
        assertThat(FactoryLedgerServiceImpl.returnCount(consume, issued, Map.of(1L, new BigDecimal("990.86"), 2L, new BigDecimal("400"))))
                .isZero();
    }

    /** 核销单带出应耗或已领不是 0 的料：只领过没用上的（BOM 改过）也要带，能核销成 0 */
    @Test
    void settleSkus_consumeOrIssuedNotZero() {
        assertThat(ProductionStockHandler.settleSkus(Map.of(1L, BigDecimal.ONE, 2L, BigDecimal.ZERO),
                Map.of(3L, new BigDecimal("2"), 4L, BigDecimal.ZERO))).containsExactlyInAnyOrder(1L, 3L);
    }
}
