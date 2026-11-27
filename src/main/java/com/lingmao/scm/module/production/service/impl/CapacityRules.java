package com.lingmao.scm.module.production.service.impl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 工厂产能怎么算（PRD 08.02，甲方 11-27 答 Q60），纯计算，单测覆盖。
 * <p>
 * 一条产能 = 一家厂 × 一个品类（或不分品类），各月的日产能；从今天到某一天能做多少 = 每天按那天所在月的日产能加起来。
 * 排一张交期 D 的单，要保证排进去以后：交期在 T 及之前、还没做完的件数 ≤ 从今天到 T 能做的，T 取 D 和所有交期在 D 之后的单的交期
 * （先到期的先做；新单交期早，会挤占后面那些单的时间）。这次最多能排的 = 这些 T 上"能做的 − 已排的"的最小值
 */
final class CapacityRules {

    private CapacityRules() {
    }

    /** 某一天用的日产能：那天所在月或之前最近一次登记的；比第一次登记还早，用第一次的 */
    static int dailyAt(NavigableMap<LocalDate, Integer> daily, LocalDate day) {
        Map.Entry<LocalDate, Integer> e = daily.floorEntry(day.withDayOfMonth(1));
        return e != null ? e.getValue() : daily.firstEntry().getValue();
    }

    /** 从 from 到 until（都含）一共能做多少；until 在 from 之前按 1 天算 */
    static long capacity(NavigableMap<LocalDate, Integer> daily, LocalDate from, LocalDate until) {
        LocalDate end = until.isBefore(from) ? from : until;
        long total = 0;
        LocalDate d = from;
        while (!d.isAfter(end)) {
            LocalDate monthEnd = d.with(TemporalAdjusters.lastDayOfMonth());
            LocalDate segEnd = monthEnd.isBefore(end) ? monthEnd : end;
            total += (ChronoUnit.DAYS.between(d, segEnd) + 1) * dailyAt(daily, d);
            d = segEnd.plusDays(1);
        }
        return total;
    }

    /**
     * 这次最多能排多少：until 是卡住的那天，capacity、load 是那天的"能做的""已排的"（不含这次），free = capacity − load
     */
    record Room(LocalDate until, long capacity, long load, long free) {
    }

    /** @param loads 这条产能管的、还没做完的：交期 → 件数（不含这次） */
    static Room room(NavigableMap<LocalDate, Integer> daily, NavigableMap<LocalDate, Long> loads, LocalDate due, LocalDate today) {
        long cum = loads.headMap(due, true).values().stream().mapToLong(Long::longValue).sum();
        long cap = capacity(daily, today, due);
        Room best = new Room(due, cap, cum, cap - cum);
        for (Map.Entry<LocalDate, Long> e : loads.tailMap(due, false).entrySet()) {
            cum += e.getValue();
            long c = capacity(daily, today, e.getKey());
            if (c - cum < best.free()) {
                best = new Room(e.getKey(), c, cum, c - cum);
            }
        }
        return best;
    }
}
