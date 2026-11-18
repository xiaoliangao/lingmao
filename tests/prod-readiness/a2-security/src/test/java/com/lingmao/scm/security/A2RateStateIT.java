package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class A2RateStateIT {
    @Autowired StringRedisTemplate redis;

    @Test void oneHundredSimultaneousRequestsShareOneSlidingWindowAcrossTwoInstances() throws Exception {
        var namespace = new DeploymentNamespace("r2_" + UUID.randomUUID().toString().replace("-", ""));
        var first = new RedisRateLimiter(redis, namespace); var second = new RedisRateLimiter(redis, namespace);
        CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                ready.countDown(); start.await();
                return (i % 2 == 0 ? first : second).acquire("export:user1:127.0.0.1", 10, Duration.ofMinutes(1));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int accepted = 0;
            for (var future : futures) { long retry = future.get(5, TimeUnit.SECONDS); if (retry == 0) accepted++; else assertThat(retry).isBetween(1L, 60L); }
            assertThat(accepted).isEqualTo(10);
        }
        assertThat(first.acquire("export:user2:127.0.0.1", 10, Duration.ofMinutes(1))).isZero();
        redis.delete(List.of(namespace.sharedKey("rate", "export:user1:127.0.0.1"), namespace.sharedKey("rate", "export:user2:127.0.0.1")));
    }

    @Test void accountCooldownIsAtomicNamespacedAndEscalatesWithoutUnboundedLockout() {
        var namespace = new DeploymentNamespace("l2_" + UUID.randomUUID().toString().replace("-", ""));
        var first = new LoginFailCounter(redis, namespace); var second = new LoginFailCounter(redis, namespace);
        String key = namespace.sharedKey("login-fail", "same-user");
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 5; i++) assertThat((i % 2 == 0 ? first : second).recordFailure("same-user")).isEqualTo(4 - i);
            assertThat(first.isLocked("same-user")).isTrue();
            assertThat(second.minutesLeft("same-user")).isEqualTo(Math.min(60, 15L << round));
            assertThat(redis.getExpire(key)).isPositive();
            // Expire the active window while preserving its one-day penalty history.
            redis.delete(key);
        }
        var other = new LoginFailCounter(redis, new DeploymentNamespace("other_" + UUID.randomUUID().toString().replace("-", "")));
        assertThat(other.isLocked("same-user")).isFalse();
        first.clear("same-user");
        assertThat(redis.hasKey(key + ":penalties")).isFalse();
        for (int i = 0; i < 5; i++) first.recordFailure("same-user");
        assertThat(first.minutesLeft("same-user")).isEqualTo(15);
        first.clear("same-user");
    }
}
