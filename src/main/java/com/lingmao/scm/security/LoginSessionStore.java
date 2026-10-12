package com.lingmao.scm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LoginSessionStore {

    private static final String KEY_PREFIX = "login:token:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public LoginSessionStore(StringRedisTemplate redis,
                             @Value("${lingmao.jwt.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** 登录成功：记一笔“这张 token 有效”，和 token 同时过期 */
    public void save(String jti, Long userId) {
        redis.opsForValue().set(KEY_PREFIX + jti, String.valueOf(userId), ttl);
    }

    /** 这张 token 还有效吗？有效返回用户 id，已退出或已过期返回 null */
    public Long getUserId(String jti) {
        String value = redis.opsForValue().get(KEY_PREFIX + jti);
        return value == null ? null : Long.valueOf(value);
    }

    /** 退出登录：删掉这一笔，token 立即失效 */
    public void remove(String jti) {
        redis.delete(KEY_PREFIX + jti);
    }
}