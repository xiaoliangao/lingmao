package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.module.board.mapper.DueWarningMapper;
import com.lingmao.scm.module.board.mapper.DueWarningMapper.ArrivalDigest;
import com.lingmao.scm.module.board.mapper.DueWarningMapper.ProductionDigest;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.service.NotifyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 每天的货期预警（RFC 019 §4）：8:30 以后每人一条，一天一次 */
class DueWarningTaskTest {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private final DueWarningMapper mapper = mock(DueWarningMapper.class);
    private final NotifyService notifyService = mock(NotifyService.class);

    private DueWarningTask at(String instant) {
        return new DueWarningTask(mapper, notifyService, Clock.fixed(Instant.parse(instant), CN));
    }

    /** 规则：8:30 前不发；关着不发 */
    @Test
    void notBeforeHalfPastEight() {
        when(notifyService.enabled(NotifyEvent.DUE_WARNING)).thenReturn(true);
        at("2026-11-27T00:29:00Z").runUntil(() -> false);   // 北京时间 8:29
        verifyNoInteractions(mapper);

        when(notifyService.enabled(NotifyEvent.DUE_WARNING)).thenReturn(false);
        at("2026-11-27T02:00:00Z").runUntil(() -> false);
        verifyNoInteractions(mapper);
    }

    /** 规则：生产单按跟单员、到料按采购员并成一人一条；全是 0 的不发；去重键是"人:日期"；同一天再跑不再查 */
    @Test
    void onePerPersonPerDay() {
        LocalDate day = LocalDate.of(2026, 11, 27);
        when(notifyService.enabled(NotifyEvent.DUE_WARNING)).thenReturn(true);
        when(mapper.selectProduction(day, day.plusDays(7))).thenReturn(List.of(
                new ProductionDigest(7, 2, 1, 0), new ProductionDigest(9, 0, 0, 0)));
        when(mapper.selectArrival(day)).thenReturn(List.of(new ArrivalDigest(7, 3), new ArrivalDigest(8, 1)));

        DueWarningTask task = at("2026-11-27T00:31:00Z");
        task.runUntil(() -> false);
        task.runUntil(() -> false);

        ArgumentCaptor<String> md = ArgumentCaptor.forClass(String.class);
        verify(notifyService).send(eq(NotifyEvent.DUE_WARNING), eq(7L), eq("7:2026-11-27"), eq("货期预警 11-27"), md.capture(),
                eq("/mobile-todo?tab=alerts"));
        assertThat(md.getValue()).contains("已逾期 **2** 张", "7 天内到期 1 张", "到料逾期 **3** 行").doesNotContain("节点延期");
        verify(notifyService).send(eq(NotifyEvent.DUE_WARNING), eq(8L), eq("8:2026-11-27"), anyString(), anyString(), anyString());
        verify(notifyService, never()).send(any(), eq(9L), anyString(), anyString(), anyString(), anyString());
        verify(notifyService, times(2)).send(any(), anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, times(1)).selectArrival(day);
    }
}
