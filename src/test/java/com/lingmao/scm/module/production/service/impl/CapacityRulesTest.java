package com.lingmao.scm.module.production.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 工厂产能怎么算（PRD 08.02，甲方 11-27 答 Q60） */
class CapacityRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    /** 跨月按各月的日产能算；12 月没登记沿用 11 月的，1 月改了用新的；比第一次登记还早用第一次的 */
    @Test
    void capacityAcrossMonths() {
        NavigableMap<LocalDate, Integer> daily = new TreeMap<>();
        daily.put(LocalDate.of(2026, 11, 1), 100);
        daily.put(LocalDate.of(2027, 1, 1), 50);
        // 11-28 ~ 11-30 三天 × 100，12 月 31 天 × 100，1-1 ~ 1-10 十天 × 50
        assertThat(CapacityRules.capacity(daily, TODAY, LocalDate.of(2027, 1, 10))).isEqualTo(300 + 3100 + 500);
        assertThat(CapacityRules.dailyAt(daily, LocalDate.of(2026, 12, 15))).isEqualTo(100);
        assertThat(CapacityRules.dailyAt(daily, LocalDate.of(2026, 10, 15))).isEqualTo(100);
        // 交期已经过了按 1 天算
        assertThat(CapacityRules.capacity(daily, TODAY, LocalDate.of(2026, 11, 20))).isEqualTo(100);
    }

    /**
     * 交期前已排的算进去，交期后的不算；但新单交期早，会挤占后面那张单：卡住的是后面那张的交期。
     * 每天 100：到 12-07 能做 1000，之前已排 600 → 交期这天还能排 400；12-10 那张 500 件，到 12-10 能做 1300，
     * 已排 600 + 500 = 1100 → 只剩 200，所以这次最多 200，卡在 12-10
     */
    @Test
    void laterOrdersLimitRoom() {
        NavigableMap<LocalDate, Integer> daily = new TreeMap<>();
        daily.put(LocalDate.of(2026, 11, 1), 100);
        NavigableMap<LocalDate, Long> loads = new TreeMap<>();
        loads.put(LocalDate.of(2026, 12, 1), 600L);
        loads.put(LocalDate.of(2026, 12, 10), 500L);
        CapacityRules.Room room = CapacityRules.room(daily, loads, LocalDate.of(2026, 12, 7), TODAY);
        assertThat(room.until()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(room.capacity()).isEqualTo(1300);
        assertThat(room.load()).isEqualTo(1100);
        assertThat(room.free()).isEqualTo(200);

        // 交期排到最后：只看到交期这天
        CapacityRules.Room late = CapacityRules.room(daily, loads, LocalDate.of(2026, 12, 20), TODAY);
        assertThat(late.until()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(late.free()).isEqualTo(2300 - 1100);
    }
}
