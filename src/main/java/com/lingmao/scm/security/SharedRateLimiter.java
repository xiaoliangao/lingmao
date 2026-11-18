package com.lingmao.scm.security;

import java.time.Duration;

public interface SharedRateLimiter {
    /** Returns zero when admitted; otherwise the whole seconds until the next admissible request. */
    long acquire(String bucket, int limit, Duration window);
}
