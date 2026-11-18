package com.lingmao.scm.security;

import lombok.RequiredArgsConstructor;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;

/**
 * 账号失败次数按命名空间原子更新。连续 5 次失败依次冷却 15、30、60 分钟；
 * 登录成功或管理员重置后清零。另有按 IP 的窗口保护登录接口。
 */
@Component
@RequiredArgsConstructor
public class LoginFailCounter {

    public static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            #!lua
            local n=redis.call('INCR',KEYS[1])
            if n == 5 then
              local p=redis.call('INCR',KEYS[2])
              if redis.call('PTTL',KEYS[2]) < 0 then redis.call('PEXPIRE',KEYS[2],86400000) end
              redis.call('PEXPIRE',KEYS[1],900000*math.pow(2,math.min(p-1,2)))
            elseif redis.call('PTTL',KEYS[1]) < 0 then redis.call('PEXPIRE',KEYS[1],900000) end
            return n
            """, Long.class);
    /** Redis 的 TTL 返回 -1：键在，但没有过期时间（-2 是键不在） */
    private static final long NO_EXPIRE = -1;

    private final StringRedisTemplate redis;
    private final DeploymentNamespace namespace;

    private String key(String username) { return namespace.sharedKey("login-fail", username); }

    /** 输错的次数到上限了吗 */
    public boolean isLocked(String username) {
        return state(() -> locked(username));
    }

    private boolean locked(String username) {
        String key = key(username);
        String count = redis.opsForValue().get(key);
        boolean locked = count != null && Integer.parseInt(count) >= MAX_FAILURES;
        if (locked) {
            // 锁住以后 login 不再调 recordFailure，没有过期时间的键只能在这里补，不然这个账号永远锁着
            repairExpire(key);
        }
        return locked;
    }

    /** 记一次输错，返回还能再试几次；返回 0 表示从现在起锁住了 */
    public int recordFailure(String username) {
        return state(() -> failure(username));
    }

    private int failure(String username) {
        String key = key(username);
        Long count = redis.execute(FAILURE, List.of(key, key + ":penalties"));
        if (count == null) throw new SecurityStateUnavailable(null);
        return Math.max(0, MAX_FAILURES - count.intValue());
    }

    /** 还要等几分钟解锁，不足一分钟按一分钟说 */
    public long minutesLeft(String username) {
        return state(() -> {
            Long seconds = redis.getExpire(key(username), TimeUnit.SECONDS);
            return seconds == null || seconds <= 0 ? 1 : (seconds + 59) / 60;
        });
    }

    /**
     * 修复装原子计数之前留下的旧键，免得它们把账号永远锁住。
     */
    private void repairExpire(String key) {
        if (Long.valueOf(NO_EXPIRE).equals(redis.getExpire(key))) {
            redis.expire(key, WINDOW);
        }
    }

    /** 清零：登录成功了，或者管理员重置了密码 */
    public void clear(String username) {
        String key = key(username);
        state(() -> redis.delete(List.of(key, key + ":penalties")));
    }

    private static <T> T state(java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (org.springframework.dao.DataAccessException | NumberFormatException failure) {
            throw new SecurityStateUnavailable(failure);
        }
    }
}
