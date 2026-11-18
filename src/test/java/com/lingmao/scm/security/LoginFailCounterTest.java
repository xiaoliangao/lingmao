package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginFailCounterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    private LoginFailCounter counter;
    private final DeploymentNamespace namespace = new DeploymentNamespace("counter-unit");

    @BeforeEach
    void setup() { counter = new LoginFailCounter(redis, namespace); }

    private String key(String username) { return namespace.sharedKey("login-fail", username); }

    private void decision(String username, long count) {
        String key = key(username);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), eq(List.of(key, key + ":penalties"))))
                .thenReturn(count);
    }

    /** 首次失败还剩四次；计数和 TTL 的原子性由真实 Redis 专项覆盖。 */
    @Test
    void recordFailure_first_setsWindowAndFourLeft() {
        decision("zhangsan", 1L);

        assertThat(counter.recordFailure("zhangsan")).isEqualTo(4);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /** 规则：第 5 次输错，还能试 0 次（锁住了）；不再重新设过期，不然一直有人试就永远不解锁 */
    @Test
    void recordFailure_fifth_noneLeftAndWindowNotExtended() {
        decision("zhangsan", 5L);

        assertThat(counter.recordFailure("zhangsan")).isZero();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /** 失败决策只调用一次共享原子脚本，并使用同租户的计数和阶梯键。 */
    @Test
    void recordFailure_usesOneNamespacedAtomicDecision() {
        decision("zhangsan", 3L);

        assertThat(counter.recordFailure("zhangsan")).isEqualTo(2);
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(key("zhangsan"), key("zhangsan") + ":penalties")));
        verify(redis, never()).opsForValue();
    }

    /** 规则：已经锁住、键又没有过期时间时，login 不会再调 recordFailure，所以 isLocked 里也要补 */
    @Test
    void isLocked_lockedKeyWithoutExpire_repaired() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(key("zhangsan"))).thenReturn("5");
        when(redis.getExpire(key("zhangsan"))).thenReturn(-1L);

        assertThat(counter.isLocked("zhangsan")).isTrue();
        verify(redis).expire(key("zhangsan"), Duration.ofMinutes(15));
    }

    /** 规则：输错满 5 次才算锁住；没有计数（从没输错或已过期）不算 */
    @Test
    void isLocked_onlyFromFiveFailures() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(key("a"))).thenReturn("4");
        when(ops.get(key("b"))).thenReturn("5");
        when(ops.get(key("c"))).thenReturn(null);
        when(redis.getExpire(key("b"))).thenReturn(600L);

        assertThat(counter.isLocked("a")).isFalse();
        assertThat(counter.isLocked("b")).isTrue();
        assertThat(counter.isLocked("c")).isFalse();
        verify(redis, never()).expire(anyString(), any(Duration.class));   // 有过期时间的不动
    }

    /** 规则：剩下的时间按分钟往上取整：61 秒说 2 分钟；键刚好过期了也说 1 分钟，不说 0 分钟 */
    @Test
    void minutesLeft_roundsUp() {
        when(redis.getExpire(key("a"), TimeUnit.SECONDS)).thenReturn(61L);
        when(redis.getExpire(key("b"), TimeUnit.SECONDS)).thenReturn(-2L);

        assertThat(counter.minutesLeft("a")).isEqualTo(2);
        assertThat(counter.minutesLeft("b")).isEqualTo(1);
    }

    @Test
    void clear_removesCurrentCooldownAndEscalationTogether() {
        counter.clear("zhangsan");
        verify(redis).delete(List.of(key("zhangsan"), key("zhangsan") + ":penalties"));
    }
}
