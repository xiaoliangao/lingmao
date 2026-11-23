package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.cache.SharedCache;
import com.lingmao.scm.module.system.service.CacheService;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.security.LoginUser;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CacheServiceImpl implements CacheService {
    private final SharedCache shared;
    private final OutboxService outbox;
    private final JsonMapper json;
    public CacheServiceImpl(SharedCache shared, OutboxService outbox, JsonMapper json) {
        this.shared = shared; this.outbox = outbox; this.json = json;
    }
    @Override public String getOrLoad(String key, Duration ttl, Duration emptyTtl, Supplier<String> loader) {
        return shared.getOrLoad(key, ttl, emptyTtl, loader);
    }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void evictAfterCommit(Collection<String> trustedKeys) {
        List<String> keys = trustedKeys.stream().distinct().toList();
        for (String key : keys) {
            outbox.enqueue("CACHE_INVALIDATE", UUID.randomUUID().toString(), json.writeValueAsString(key),
                    LoginUser.currentUserId(), MDC.get("traceId"));
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (String key : keys) {
                    try { shared.invalidate(key); }
                    catch (org.springframework.dao.DataAccessException unavailable) {
                        // 业务已经提交。相同事务内的发件箱稍后重试，不把网络故障伪装成业务回滚。
                    }
                }
            }
        });
    }
}
