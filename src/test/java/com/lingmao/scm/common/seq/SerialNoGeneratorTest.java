package com.lingmao.scm.common.seq;

import com.lingmao.scm.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 只测拼号：日期取哪一天、补几位、超了怎么办。取号本身（并发不重复、跟着事务回滚、跨天从 1 开始）
 * 要真数据库才测得出来，在 SerialNoGeneratorIT
 */
@ExtendWith(MockitoExtension.class)
class SerialNoGeneratorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    /** 东八区 11-05 的凌晨 0:30 = UTC 11-04 16:30：日期按东八区算，是 11-05 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T16:30:00Z"), ZoneId.of("Asia/Shanghai"));

    private SerialNoGenerator generator() {
        return new SerialNoGenerator(jdbcTemplate, clock);
    }

    /** 规则：单据号 = 前缀 + 东八区的今天 + 4 位流水，计数的 key 是前缀 + 日期 */
    @Test
    void daily_prefixDateAndFourDigits() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("CG20261105"))).thenReturn(12);

        assertThat(generator().daily("CG")).isEqualTo("CG202611050012");
    }

    /** 规则：流水超过定长报 1000，不多出一位（款号 2 位，第 100 个） */
    @Test
    void next_overWidth_throws() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("FS26110807"))).thenReturn(100);

        assertThatThrownBy(() -> generator().next("FS26110807", 2))
                .isInstanceOf(BizException.class)
                .hasMessage("编号 FS26110807 的流水号用完了（最多 2 位）");
    }
}
