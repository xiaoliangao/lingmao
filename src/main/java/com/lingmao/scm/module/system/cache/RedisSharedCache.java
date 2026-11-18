package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Local coalescing plus a Redis lease protects multiple application instances. */
@Component
public final class RedisSharedCache implements SharedCache {
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            #!lua
            if redis.call('GET', KEYS[3]) ~= ARGV[4] then return 0 end
            if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[1] then return 0 end
            redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2]); return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            #!lua flags=allow-oom
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            #!lua
            redis.call('INCR', KEYS[2]); redis.call('PEXPIRE', KEYS[2], 120000)
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private static final Duration LEASE = Duration.ofSeconds(45);
    private final StringRedisTemplate redis;
    private final DeploymentNamespace namespace;
    private final ConcurrentHashMap<String, CompletableFuture<String>> flights = new ConcurrentHashMap<>();
    private final Semaphore degradedLoads = new Semaphore(8);
    private final AtomicLong lastWarning = new AtomicLong();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisSharedCache.class);

    public RedisSharedCache(StringRedisTemplate redis, DeploymentNamespace namespace) {
        this.redis = redis;
        this.namespace = namespace;
    }

    @Override
    public String getOrLoad(String key, Duration ttl, Duration emptyTtl, Supplier<String> loader) {
        checkKey(key);
        // A loader inside a write transaction may see uncommitted data. Do not publish or join it.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> active = flights.putIfAbsent(key, mine);
        if (active != null) return await(active);
        try {
            String value;
            try { value = fillWithLease(key, ttl, emptyTtl, loader); }
            catch (CacheUnavailable unavailable) { value = degradedLoad(loader); }
            mine.complete(value);
            return value;
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            flights.remove(key, mine);
        }
    }

    private String fillWithLease(String key, Duration ttl, Duration emptyTtl, Supplier<String> loader) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String lock = key + ":fill";
        String versionKey = key + ":generation";
        while (System.nanoTime() < deadline) {
            String cached = redisCall(() -> redis.opsForValue().get(key));
            if (cached != null) return cached;
            String owner = UUID.randomUUID().toString();
            if (Boolean.TRUE.equals(redisCall(() -> redis.opsForValue().setIfAbsent(lock, owner, LEASE)))) {
                try {
                    cached = redisCall(() -> redis.opsForValue().get(key));
                    if (cached != null) return cached;
                    String generation = redisCall(() -> redis.opsForValue().get(versionKey));
                    String value = loader.get();
                    if (value == null) value = "null";
                    Duration lifetime = "null".equals(value) || "[]".equals(value) ? emptyTtl : ttl;
                    long millis = jitter(lifetime);
                    // If an update committed while the loader ran, the old result must not be reinserted.
                    try {
                        redis.execute(PUBLISH, List.of(key, versionKey, lock), generation == null ? "0" : generation,
                                value, Long.toString(millis), owner);
                    } catch (org.springframework.dao.DataAccessException unavailable) { warnDegraded(); }
                    return value;
                } finally {
                    try { redis.execute(RELEASE, List.of(lock), owner); }
                    catch (org.springframework.dao.DataAccessException unavailable) { warnDegraded(); }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BizException(503, "请求已取消，请稍后重试");
            }
        }
        throw new BizException(503, "数据正在更新，请稍后重试");
    }

    private <T> T redisCall(Supplier<T> operation) {
        try { return operation.get(); }
        catch (org.springframework.dao.DataAccessException failure) { warnDegraded(); throw new CacheUnavailable(); }
    }

    private String degradedLoad(Supplier<String> loader) {
        boolean entered;
        try { entered = degradedLoads.tryAcquire(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BizException(503, "请求已取消，请稍后重试"); }
        if (!entered) throw new BizException(503, "数据查询繁忙，请稍后重试");
        try { String value = loader.get(); return value == null ? "null" : value; }
        finally { degradedLoads.release(); }
    }

    private void warnDegraded() {
        long now = System.nanoTime(), before = lastWarning.get();
        if (now - before > TimeUnit.SECONDS.toNanos(30) && lastWarning.compareAndSet(before, now))
            log.warn("Shared cache unavailable; bounded local coalescing is active");
    }

    private static final class CacheUnavailable extends RuntimeException {}

    private static String await(CompletableFuture<String> result) {
        try {
            return result.get(6, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "请求已取消，请稍后重试");
        } catch (TimeoutException timeout) {
            throw new BizException(503, "数据正在更新，请稍后重试");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cache load failed", failed.getCause());
        }
    }

    public static long jitter(Duration ttl) {
        long millis = Math.max(1000, ttl.toMillis());
        return millis + ThreadLocalRandom.current().nextLong(Math.max(1, millis / 5));
    }

    @Override
    public void invalidate(String key) {
        checkKey(key);
        try { redis.execute(INVALIDATE, List.of(key, key + ":generation")); }
        catch (org.springframework.dao.DataAccessException unavailable) {
            // DEL is allowed under noeviction/OOM. Removing both value and owner lease also fences an older loader.
            // A disconnected Redis still throws, leaving the committed outbox message for durable retry.
            redis.delete(List.of(key, key + ":fill"));
            warnDegraded();
        }
    }

    private void checkKey(String key) {
        if (key == null || !key.startsWith("scm:" + namespace.tenant() + ":company:") || key.length() > 900) {
            throw new IllegalArgumentException("Cache key has no trusted deployment namespace");
        }
    }
}
