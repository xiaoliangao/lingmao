package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.system.cache.RedisSharedCache;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.impl.CacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Redis and actual TagRepository SQL; the counter observes the loader, never substitutes query results. */
@IntegrationTest
class S2TagCacheIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DeploymentNamespace namespace;
    @Autowired TagRepository repository;
    @Autowired OutboxService outbox;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactions;

    @Test void oneHundredConcurrentMissesAcrossTwoCacheInstancesExecuteOneRealTagQuery() throws Exception {
        String name = "singleflight-" + suffix(); long id = insert(name);
        TagCache first = cache(), second = cache();
        AtomicInteger loads = new AtomicInteger(); List<Integer> postgresBackends = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
        List<List<TagVO>> actual = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                ready.countDown(); start.await();
                return (i % 2 == 0 ? first : second).options(name, 50, List.of(), () -> {
                    loads.incrementAndGet();
                    postgresBackends.add(jdbc.queryForObject("SELECT pg_backend_pid() FROM pg_sleep(0.2)", Integer.class));
                    return rows(name);
                });
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var request : requests) actual.add(request.get(10, TimeUnit.SECONDS));
        }
        assertThat(loads).hasValue(1); assertThat(postgresBackends).hasSize(1);
        assertThat(actual).hasSize(100).allSatisfy(rows -> assertThat(rows).containsExactly(new TagVO(id, name, false, 0)));
        long ttl = redis.getExpire(payloadKey(name, 50, List.of()), TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(110_000L, 144_000L);
        write("cache-100-real-query.json", Map.of("requests", 100, "cacheInstances", 2, "realRepositoryLoads", loads.get(),
                "postgresBackends", postgresBackends, "ttlMs", ttl, "result", actual.getFirst()));
    }

    @Test void emptyPrefixResultHasShortTtlAndCanonicalKeysDoNotLeakRawInput() throws Exception {
        TagCache cache = cache(); String prefix = "absent-" + suffix(); AtomicInteger loads = new AtomicInteger();
        assertThat(cache.options(prefix, 50, List.of(), () -> { loads.incrementAndGet(); return rows(prefix); })).isEmpty();
        assertThat(cache.options(prefix, 50, List.of(), () -> { loads.incrementAndGet(); return rows(prefix); })).isEmpty();
        assertThat(loads).hasValue(1);
        String key = payloadKey(prefix, 50, List.of());
        assertThat(key).startsWith("scm:" + namespace.tenant() + ":company:shared:").doesNotContain(prefix);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS); assertThat(ttl).isBetween(1_000L, 6_000L);
        write("cache-empty-budget.json", Map.of("realRepositoryLoads", 1, "ttlMs", ttl, "trustedPrefix", "scm:" + namespace.tenant() + ":company:shared:"));
    }

    @Test void committedLibraryEpochPreventsASlowOldFillFromBecomingTheNextRead() throws Exception {
        String original = "race-" + suffix(), renamed = original + "-new"; long id = insert(original);
        TagCache first = cache(), second = cache();
        CountDownLatch loaded = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = pool.submit(() -> first.options(original, 50, List.of(), () -> {
                List<TagVO> captured = rows(original); loaded.countDown();
                try { if (!finish.await(8, TimeUnit.SECONDS)) throw new AssertionError("slow source not released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return captured;
            }));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(transactions).executeWithoutResult(status -> { repository.rename(id, 0, renamed); second.libraryChanged(); });
            finish.countDown();
            assertThat(old.get(8, TimeUnit.SECONDS)).containsExactly(new TagVO(id, original, false, 0));
            List<TagVO> next = second.options(original, 50, List.of(), () -> rows(original));
            assertThat(next).containsExactly(new TagVO(id, renamed, false, 1));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox WHERE kind='CACHE_INVALIDATE' AND payload::text LIKE ?", Long.class,
                    "%" + namespace.sharedKey("public-tags-v1", "library-epoch") + "%")).isPositive();
            write("cache-slow-fill-commit.json", Map.of("before", old.get(), "nextRead", next, "actualDatabaseName", jdbc.queryForObject("SELECT name FROM biz_tag WHERE id=?", String.class, id)));
        } finally { finish.countDown(); }
    }

    private TagCache cache() {
        return new TagCache(new CacheServiceImpl(new RedisSharedCache(redis, namespace), outbox, json), namespace, json);
    }
    private List<TagVO> rows(String prefix) {
        return repository.searchActive(prefix, 50).stream().map(tag -> new TagVO(tag.id(), tag.name(), tag.archived(), tag.version())).toList();
    }
    private long insert(String name) {
        return jdbc.queryForObject("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES(?,?,?) RETURNING id", Long.class, namespace.tenant(), name, name);
    }
    private String payloadKey(String prefix, int size, List<Long> included) throws Exception {
        String epoch = redis.opsForValue().get(namespace.sharedKey("public-tags-v1", "library-epoch"));
        assertThat(epoch).isNotNull();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(prefix, size, included)).getBytes(StandardCharsets.UTF_8)));
        return namespace.sharedKey("public-tags-v1", "options:" + epoch + ":" + fingerprint);
    }
    private static String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private void write(String name, Object value) throws Exception {
        Path dir = Path.of("target/a2-tags"); Files.createDirectories(dir); Files.writeString(dir.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
