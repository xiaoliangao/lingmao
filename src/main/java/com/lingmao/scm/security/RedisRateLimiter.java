package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Bounded sliding windows use Redis's clock, so different application instances make one decision. */
@Component
public class RedisRateLimiter implements SharedRateLimiter {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            #!lua
            local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
            local window=tonumber(ARGV[2]); redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-window)
            if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[1]) then
              local first=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES')
              return math.max(1,math.ceil((tonumber(first[2])+window-now)/1000))
            end
            redis.call('ZADD',KEYS[1],now,ARGV[3]); redis.call('PEXPIRE',KEYS[1],window); return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final DeploymentNamespace namespace;

    public RedisRateLimiter(StringRedisTemplate redis, DeploymentNamespace namespace) {
        this.redis = redis; this.namespace = namespace;
    }

    @Override public long acquire(String bucket, int limit, Duration window) {
        if (limit < 1 || limit > 10000 || window.isZero() || window.isNegative() || window.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Invalid rate window");
        try {
            Long retry = redis.execute(ACQUIRE, List.of(namespace.sharedKey("rate", bucket)), Integer.toString(limit),
                    Long.toString(window.toMillis()), UUID.randomUUID().toString());
            if (retry == null) throw new SecurityStateUnavailable(null);
            return retry;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new SecurityStateUnavailable(failure);
        }
    }
}
