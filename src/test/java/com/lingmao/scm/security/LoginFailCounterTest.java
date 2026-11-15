package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginFailCounterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @InjectMocks
    private LoginFailCounter counter;

    /** 规则：第一次输错，计数键设 15 分钟过期，还能再试 4 次 */
    @Test
    void recordFailure_first_setsWindowAndFourLeft() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("login:fail:zhangsan")).thenReturn(1L);

        assertThat(counter.recordFailure("zhangsan")).isEqualTo(4);
        verify(redis).expire("login:fail:zhangsan", Duration.ofMinutes(15));
    }

    /** 规则：第 5 次输错，还能试 0 次（锁住了）；不再重新设过期，不然一直有人试就永远不解锁 */
    @Test
    void recordFailure_fifth_noneLeftAndWindowNotExtended() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("login:fail:zhangsan")).thenReturn(5L);
        when(redis.getExpire("login:fail:zhangsan")).thenReturn(300L);     // 还有 5 分钟过期

        assertThat(counter.recordFailure("zhangsan")).isZero();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /**
     * 规则（RFC 001 §18）：第一次输错时 INCR 成功、EXPIRE 前 Redis 断了，键就没有过期时间（TTL -1）。
     * 之后再输错，发现没有过期时间就补 15 分钟，不然锁上以后永远不解锁
     */
    @Test
    void recordFailure_keyWithoutExpire_repaired() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("login:fail:zhangsan")).thenReturn(3L);
        when(redis.getExpire("login:fail:zhangsan")).thenReturn(-1L);

        assertThat(counter.recordFailure("zhangsan")).isEqualTo(2);
        verify(redis).expire("login:fail:zhangsan", Duration.ofMinutes(15));
    }

    /** 规则：已经锁住、键又没有过期时间时，login 不会再调 recordFailure，所以 isLocked 里也要补 */
    @Test
    void isLocked_lockedKeyWithoutExpire_repaired() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("login:fail:zhangsan")).thenReturn("5");
        when(redis.getExpire("login:fail:zhangsan")).thenReturn(-1L);

        assertThat(counter.isLocked("zhangsan")).isTrue();
        verify(redis).expire("login:fail:zhangsan", Duration.ofMinutes(15));
    }

    /** 规则：输错满 5 次才算锁住；没有计数（从没输错或已过期）不算 */
    @Test
    void isLocked_onlyFromFiveFailures() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("login:fail:a")).thenReturn("4");
        when(ops.get("login:fail:b")).thenReturn("5");
        when(ops.get("login:fail:c")).thenReturn(null);
        when(redis.getExpire("login:fail:b")).thenReturn(600L);

        assertThat(counter.isLocked("a")).isFalse();
        assertThat(counter.isLocked("b")).isTrue();
        assertThat(counter.isLocked("c")).isFalse();
        verify(redis, never()).expire(anyString(), any(Duration.class));   // 有过期时间的不动
    }

    /** 规则：剩下的时间按分钟往上取整：61 秒说 2 分钟；键刚好过期了也说 1 分钟，不说 0 分钟 */
    @Test
    void minutesLeft_roundsUp() {
        when(redis.getExpire("login:fail:a", TimeUnit.SECONDS)).thenReturn(61L);
        when(redis.getExpire("login:fail:b", TimeUnit.SECONDS)).thenReturn(-2L);

        assertThat(counter.minutesLeft("a")).isEqualTo(2);
        assertThat(counter.minutesLeft("b")).isEqualTo(1);
    }
}
