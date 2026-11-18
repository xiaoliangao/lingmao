package com.lingmao.scm.module.system.cache;

import java.time.Duration;
import java.util.function.Supplier;

/** 可替换的共享缓存；调用方传进来的键已经带命名空间。 */
public interface SharedCache {
    String getOrLoad(String key, Duration ttl, Duration emptyTtl, Supplier<String> loader);
    void invalidate(String key);
}
