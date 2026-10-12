package com.lingmao.scm.common.seq;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 单号生成器：前缀 + 日期 + 4 位当天流水号，如 PO202610120001。
 * 流水号用 Redis 的 INCR：原子操作，多个请求同时要号也不会拿到同一个
 */
@Component
@RequiredArgsConstructor
public class SerialNoGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;   // yyyyMMdd

    private final StringRedisTemplate redis;

    /** 取下一个单号。prefix 如 PO（采购单）、IN（入库单） */
    public String next(String prefix) {
        String day = LocalDate.now(ZONE).format(DAY);
        String key = "seq:" + prefix + ":" + day;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            // 当天第一个号：给计数器设过期时间，两天后自动清掉，不会越积越多
            redis.expire(key, Duration.ofDays(2));
        }
        return prefix + day + String.format("%04d", n);
    }
}
