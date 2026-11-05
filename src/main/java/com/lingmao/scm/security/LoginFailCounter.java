package com.lingmao.scm.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败计数：Redis 里 login:fail:{用户名} → 输错了几次。第一次输错时设 15 分钟过期，到期自动清零。
 * 满 5 次锁住，一直锁到这个键过期（从第一次输错算起 15 分钟）。
 * 为什么按用户名、不按 IP：甲方办公室出口是同一个 IP，按 IP 锁会把整个公司锁在外面。
 * 代价是别人故意输错能把某个账号锁一阵子，所以管理员给他重置密码时一并清零。
 */
@Component
@RequiredArgsConstructor
public class LoginFailCounter {

    public static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;

    /** 输错的次数到上限了吗 */
    public boolean isLocked(String username) {
        String count = redis.opsForValue().get(KEY_PREFIX + username);
        return count != null && Integer.parseInt(count) >= MAX_FAILURES;
    }

    /** 记一次输错，返回还能再试几次；返回 0 表示从现在起锁住了 */
    public int recordFailure(String username) {
        String key = KEY_PREFIX + username;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只在第一次设过期：之后每次都设的话，一直有人在试，就永远不解锁
            redis.expire(key, WINDOW);
        }
        return count == null ? MAX_FAILURES : Math.max(0, MAX_FAILURES - count.intValue());
    }

    /** 还要等几分钟解锁，不足一分钟按一分钟说 */
    public long minutesLeft(String username) {
        Long seconds = redis.getExpire(KEY_PREFIX + username, TimeUnit.SECONDS);
        return seconds == null || seconds <= 0 ? 1 : (seconds + 59) / 60;
    }

    /** 清零：登录成功了，或者管理员重置了密码 */
    public void clear(String username) {
        redis.delete(KEY_PREFIX + username);
    }
}
