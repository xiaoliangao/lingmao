package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.module.board.mapper.ReportMapper.FactoryOrderFact;
import com.lingmao.scm.module.board.mapper.ReportMapper.PurchaseLineFact;
import com.lingmao.scm.module.board.service.impl.ReportRules.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 报表里准时不准时怎么判（RFC 019 §3.1） */
class ReportRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 10);
    private static final LocalDate DUE = LocalDate.of(2026, 12, 5);

    /** 到齐不晚于交期准时；晚了按晚几天；没到齐、过了交期算到今天；交期没到、没到齐不算 */
    @Test
    void purchaseOutcomes() {
        assertThat(ReportRules.purchase(line("100", DUE, null, "RUNNING"), TODAY).outcome()).isEqualTo(Outcome.ON_TIME);
        assertThat(ReportRules.purchase(line("100", DUE.plusDays(3), null, "RUNNING"), TODAY))
                .isEqualTo(new ReportRules.Judged(Outcome.LATE, 3));
        assertThat(ReportRules.purchase(line("100", null, null, "RUNNING"), TODAY)).isEqualTo(new ReportRules.Judged(Outcome.LATE, 5));
        assertThat(ReportRules.purchase(new PurchaseLineFact(1, 2, TODAY.plusDays(1), "RUNNING", BigDecimal.TEN, null, null), TODAY).outcome())
                .isEqualTo(Outcome.PENDING);
        // 全靠别的单超收冲抵到了：准时
        assertThat(ReportRules.purchase(line("0", null, null, "RUNNING"), TODAY).outcome()).isEqualTo(Outcome.ON_TIME);
    }

    /** 完成、结案了没到齐：按最后一次到货判；一次都没到的是取消掉的行，不算 */
    @Test
    void finishedShort() {
        assertThat(ReportRules.purchase(line("100", null, DUE.minusDays(1), "CLOSED"), TODAY).outcome()).isEqualTo(Outcome.ON_TIME);
        assertThat(ReportRules.purchase(line("100", null, null, "CLOSED"), TODAY).outcome()).isEqualTo(Outcome.SKIP);
        assertThat(ReportRules.production(new FactoryOrderFact(1, 2, DUE, "DONE", 100, null, DUE.plusDays(2), 0), TODAY))
                .isEqualTo(new ReportRules.Judged(Outcome.LATE, 2));
        assertThat(ReportRules.pct(1, 3)).isEqualByComparingTo("33.3");
        assertThat(ReportRules.pct(0, 0)).isNull();
        assertThat(ReportRules.pct(new BigDecimal("-50"), new BigDecimal("200"))).isEqualByComparingTo("-25.0");
    }

    private static PurchaseLineFact line(String need, LocalDate full, LocalDate last, String status) {
        return new PurchaseLineFact(1, 2, DUE, status, new BigDecimal(need), full, last);
    }
}
