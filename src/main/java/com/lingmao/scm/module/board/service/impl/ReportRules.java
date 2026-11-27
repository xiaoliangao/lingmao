package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.module.board.mapper.ReportMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** 报表里"准时不准时"怎么判（RFC 019 §3.1），纯计算，单测覆盖 */
final class ReportRules {

    private ReportRules() {
    }

    /** 单子完成、结案了：没到齐也算结束，按最后一次到货判 */
    private static final Set<String> FINISHED = Set.of("DONE", "CLOSED");

    enum Outcome { ON_TIME, LATE, PENDING, SKIP }

    /** @param lateDays 逾期几天（没到齐的算到今天）；不逾期是 0 */
    record Judged(Outcome outcome, long lateDays) {
    }

    /** 到齐（或结束）的那天不晚于交期 = 准时；过了交期还没到齐 = 逾期；交期没到、没到齐 = 还在进行，不算 */
    static Judged judge(LocalDate due, LocalDate arrived, LocalDate today) {
        if (arrived != null) {
            return arrived.isAfter(due) ? new Judged(Outcome.LATE, ChronoUnit.DAYS.between(due, arrived)) : new Judged(Outcome.ON_TIME, 0);
        }
        if (due.isBefore(today)) {
            return new Judged(Outcome.LATE, ChronoUnit.DAYS.between(due, today));
        }
        return new Judged(Outcome.PENDING, 0);
    }

    /** 采购行：全靠别的单超收冲抵到了的算准时；完成、结案了一次都没到的是取消掉的行，不算 */
    static Judged purchase(ReportMapper.PurchaseLineFact f, LocalDate today) {
        if (f.need().signum() <= 0) {
            return new Judged(Outcome.ON_TIME, 0);
        }
        LocalDate arrived = f.fullDate();
        if (arrived == null && FINISHED.contains(f.status())) {
            if (f.lastDate() == null) {
                return new Judged(Outcome.SKIP, 0);
            }
            arrived = f.lastDate();
        }
        return judge(f.dueDate(), arrived, today);
    }

    /** 生产单：良品回齐那天；完成、结案了没回齐按最后一次回货，一次都没回的不算 */
    static Judged production(ReportMapper.FactoryOrderFact f, LocalDate today) {
        LocalDate arrived = f.fullDate();
        if (arrived == null && FINISHED.contains(f.status())) {
            if (f.lastDate() == null) {
                return new Judged(Outcome.SKIP, 0);
            }
            arrived = f.lastDate();
        }
        return judge(f.dueDate(), arrived, today);
    }

    /** part ÷ whole 的百分数，一位小数；whole 是 0 时 null */
    static BigDecimal pct(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** 金额的百分数（毛利率），一位小数；分母是 0 时 null */
    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
