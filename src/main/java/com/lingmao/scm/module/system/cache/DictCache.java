package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Namespaced, coalesced dictionary reads; committed changes invalidate the shared generation. */
@Component
public class DictCache {

    /** 兜底的过期时间：万一哪里漏删了，最多一天后也会自己更新 */
    private static final Duration TTL = Duration.ofHours(24);

    private final JsonMapper jsonMapper;
    private final SharedCache shared;
    private final DeploymentNamespace namespace;
    private final com.lingmao.scm.module.system.service.OutboxService outbox;

    public DictCache(JsonMapper jsonMapper, SharedCache shared, DeploymentNamespace namespace,
                     com.lingmao.scm.module.system.service.OutboxService outbox) {
        this.jsonMapper = jsonMapper;
        this.shared = shared;
        this.namespace = namespace;
        this.outbox = outbox;
    }

    public List<DictOption> getOrLoad(String typeCode, Supplier<List<DictOption>> loader) {
        String encoded = shared.getOrLoad(key(typeCode), TTL, Duration.ofSeconds(30),
                () -> jsonMapper.writeValueAsString(loader.get()));
        return jsonMapper.readValue(encoded, new TypeReference<List<DictOption>>() {});
    }

    private String key(String typeCode) {
        if (typeCode == null || !typeCode.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new com.lingmao.scm.common.exception.BizException(400, "字典类型格式不正确");
        }
        return namespace.sharedKey("dict-v3", typeCode);
    }

    /**
     * 删缓存，但等数据库事务提交以后再删。
     * 提交前就删的话，另一个请求可能在这个空档里读到旧数据、又放回缓存，旧数据就一直留着了
     */
    public void evictAfterCommit(String... typeCodes) {
        List<String> keys = Arrays.stream(typeCodes).filter(Objects::nonNull).distinct()
                .map(this::key).toList();
        var pending = keys.stream().map(key -> new Pending(key, outbox.enqueue("CACHE_INVALIDATE",
                java.util.UUID.randomUUID().toString(), jsonMapper.writeValueAsString(key),
                com.lingmao.scm.security.LoginUser.currentUserId(), org.slf4j.MDC.get("traceId")))).toList();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pending.forEach(DictCache.this::invalidate);
                }
            });
        } else {
            pending.forEach(this::invalidate);
        }
    }

    private void invalidate(Pending pending) {
        try { shared.invalidate(pending.key()); }
        catch (org.springframework.dao.DataAccessException unavailable) {
            // The committed outbox row remains pending and survives process termination.
        }
        // The worker repeats this idempotent eviction and acknowledges it after this transaction releases its connection.
    }
    private record Pending(String key, long id) {}
}
