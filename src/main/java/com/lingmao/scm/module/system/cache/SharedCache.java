package com.lingmao.scm.module.system.cache;

import java.time.Duration;
import java.util.function.Supplier;

/** Replaceable shared cache; callers supply already namespaced keys. */
public interface SharedCache {
    String getOrLoad(String key, Duration ttl, Duration emptyTtl, Supplier<String> loader);
    void invalidate(String key);
}
