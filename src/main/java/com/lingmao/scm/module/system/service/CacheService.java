package com.lingmao.scm.module.system.service;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

/** 跨域缓存入口，底层仍只有现有 SharedCache 与 CACHE_INVALIDATE 发件箱处理器。 */
public interface CacheService {
    String getOrLoad(String trustedKey, Duration ttl, Duration emptyTtl, Supplier<String> loader);
    /** 必须在业务事务内调用；事务提交后才清缓存，已提交的 Outbox 负责失败恢复。 */
    void evictAfterCommit(Collection<String> trustedKeys);
}
