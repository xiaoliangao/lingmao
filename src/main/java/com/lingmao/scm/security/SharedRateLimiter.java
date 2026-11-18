package com.lingmao.scm.security;

import java.time.Duration;

public interface SharedRateLimiter {
    /** 放行返回 0；否则返回还要等几秒（整数）。 */
    long acquire(String bucket, int limit, Duration window);
}
