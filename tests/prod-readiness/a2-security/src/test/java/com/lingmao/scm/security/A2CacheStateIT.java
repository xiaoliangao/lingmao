package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.cache.DictCache;
import com.lingmao.scm.module.system.cache.RedisSharedCache;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T08: real Redis and PostgreSQL, two independent cache objects and one hundred competing callers. */
@IntegrationTest
class A2CacheStateIT {
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired DictDataService dictionaries;
    @Autowired DeploymentNamespace deployment;

    @Test void twoInstancesCoalesceOneHundredMissesAndIsolateNamespaces() throws Exception {
        var namespace = new DeploymentNamespace("a2_" + UUID.randomUUID().toString().replace("-", ""));
        var first = new RedisSharedCache(redis, namespace);
        var second = new RedisSharedCache(redis, namespace);
        String key = namespace.sharedKey("test", "same-name");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                ready.countDown(); start.await();
                return (i % 2 == 0 ? first : second).getOrLoad(key, Duration.ofSeconds(60), Duration.ofSeconds(3), () -> {
                    loads.incrementAndGet();
                    return jdbc.queryForObject("SELECT 'one-source' FROM pg_sleep(0.15)", String.class);
                });
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) assertThat(future.get(8, TimeUnit.SECONDS)).isEqualTo("one-source");
        }
        assertThat(loads).hasValue(1);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(45_000L, 72_000L);
        var otherNamespace = new DeploymentNamespace("b2_" + UUID.randomUUID().toString().replace("-", ""));
        var other = new RedisSharedCache(redis, otherNamespace);
        assertThat(other.getOrLoad(otherNamespace.sharedKey("test", "same-name"), Duration.ofMinutes(1), Duration.ofSeconds(3), () -> "other")).isEqualTo("other");
        assertThatThrownBy(() -> other.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> "leak"))
                .isInstanceOf(IllegalArgumentException.class);
        redis.delete(List.of(key, otherNamespace.sharedKey("test", "same-name")));
    }

    @Test void negativeResultsUseShortTtlAndFailedLoadsReleaseTheirLease() {
        var namespace = new DeploymentNamespace("n2_" + UUID.randomUUID().toString().replace("-", ""));
        var cache = new RedisSharedCache(redis, namespace);
        String key = namespace.sharedKey("test", "empty");
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.getOrLoad(key, Duration.ofHours(1), Duration.ofSeconds(3), () -> { loads.incrementAndGet(); return "[]"; })).isEqualTo("[]");
        assertThat(cache.getOrLoad(key, Duration.ofHours(1), Duration.ofSeconds(3), () -> { loads.incrementAndGet(); return "wrong"; })).isEqualTo("[]");
        assertThat(loads).hasValue(1);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1_000L, 3_600L);
        cache.invalidate(key);
        assertThatThrownBy(() -> cache.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> { throw new IllegalStateException("source failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> "recovered")).isEqualTo("recovered");
        redis.delete(List.of(key, key + ":generation"));
    }

    @Test void realRedisOomUsesOneBoundedSourceLoadPerInstanceAndCanStillEvictOldData() throws Exception {
        var namespace = new DeploymentNamespace("oom_" + UUID.randomUUID().toString().replace("-", ""));
        var first = new RedisSharedCache(redis, namespace); var second = new RedisSharedCache(redis, namespace);
        String key = namespace.sharedKey("test", "old");
        assertThat(first.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> "old")).isEqualTo("old");
        AtomicInteger loads = new AtomicInteger(); CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            String maximum = connection.serverCommands().getConfig("maxmemory").getProperty("maxmemory");
            String policy = connection.serverCommands().getConfig("maxmemory-policy").getProperty("maxmemory-policy");
            try {
                connection.serverCommands().setConfig("maxmemory-policy", "noeviction"); connection.serverCommands().setConfig("maxmemory", "1");
                first.invalidate(key); assertThat(redis.hasKey(key)).isFalse();
                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    var requests = java.util.stream.IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                        ready.countDown(); start.await();
                        return (i % 2 == 0 ? first : second).getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> {
                            loads.incrementAndGet(); return jdbc.queryForObject("SELECT 'fresh' FROM pg_sleep(0.15)", String.class);
                        });
                    })).toList();
                    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
                    for (var request : requests) assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("fresh");
                }
                assertThat(loads).hasValue(2); assertThat(redis.hasKey(key)).isFalse();
            } finally { connection.serverCommands().setConfig("maxmemory", maximum); connection.serverCommands().setConfig("maxmemory-policy", policy); }
        }
    }

    @Test void rollbackNeitherPublishesNorInvalidatesAndCommittedInvalidationWinsAgainstSlowFill() throws Exception {
        var namespace = new DeploymentNamespace("t2_" + UUID.randomUUID().toString().replace("-", ""));
        var shared = new RedisSharedCache(redis, namespace);
        var dict = new DictCache(json, shared, namespace,
                new com.lingmao.scm.module.system.service.impl.OutboxStore(jdbc, namespace, transactions));
        String key = namespace.sharedKey("dict-v3", "test");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            dict.getOrLoad("test", List::of);
            dict.evictAfterCommit("test");
            status.setRollbackOnly();
        });
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(key + ":generation")).isFalse();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = pool.submit(() -> shared.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> {
                entered.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("fill never released"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return "old";
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT 1", Integer.class);
                dict.evictAfterCommit("test");
                assertThat(redis.hasKey(key + ":generation")).isFalse();
            });
            finish.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("old");
        }
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(shared.getOrLoad(key, Duration.ofMinutes(1), Duration.ofSeconds(3), () -> "committed")).isEqualTo("committed");
        redis.delete(List.of(key, key + ":generation"));
    }

    @Test void actualDictionaryUpdatesPreserveCommittedOptionsOnRollbackAndInvalidateOnCommit() {
        String type = "a2_" + UUID.randomUUID().toString().replace("-", "");
        String key = deployment.sharedKey("dict-v3", type);
        jdbc.update("INSERT INTO sys_dict_type(code,name) VALUES(?,?)", type, "A2 transaction dictionary");
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode(type); req.setValue("one"); req.setLabel("original");
        long id = dictionaries.create(req);
        try {
            assertThat(dictionaries.listByType(type)).containsExactly(new DictOption("original", "one", 1));
            String committedCache = redis.opsForValue().get(key);
            var tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                req.setLabel("rolled back"); dictionaries.update(id, req);
                assertThat(dictionaries.listByType(type)).containsExactly(new DictOption("rolled back", "one", 1));
                assertThat(redis.opsForValue().get(key)).isEqualTo(committedCache);
                status.setRollbackOnly();
            });
            assertThat(dictionaries.listByType(type)).containsExactly(new DictOption("original", "one", 1));
            assertThat(jdbc.queryForObject("SELECT label FROM sys_dict_data WHERE id=?", String.class, id)).isEqualTo("original");
            req.setLabel("committed"); dictionaries.update(id, req);
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(dictionaries.listByType(type)).containsExactly(new DictOption("committed", "one", 1));
        } finally {
            jdbc.update("DELETE FROM sys_dict_data WHERE id=?", id);
            jdbc.update("DELETE FROM sys_dict_type WHERE code=?", type);
            redis.delete(List.of(key, key + ":generation"));
        }
    }
}
