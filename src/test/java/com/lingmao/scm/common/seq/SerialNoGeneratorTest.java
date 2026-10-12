package com.lingmao.scm.common.seq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SerialNoGeneratorTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @InjectMocks
    private SerialNoGenerator generator;

    private final String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE);

    /** 规则：当天第一个号是 0001，并给计数器设两天过期 */
    @Test
    void next_firstOfDay_setsExpire() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("seq:PO:" + today)).thenReturn(1L);

        assertThat(generator.next("PO")).isEqualTo("PO" + today + "0001");
        verify(redis).expire("seq:PO:" + today, Duration.ofDays(2));
    }

    /** 规则：后面的号补足 4 位，不再重复设过期 */
    @Test
    void next_laterOfDay_padsAndNoExpire() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("seq:PO:" + today)).thenReturn(12L);

        assertThat(generator.next("PO")).isEqualTo("PO" + today + "0012");
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }
}
