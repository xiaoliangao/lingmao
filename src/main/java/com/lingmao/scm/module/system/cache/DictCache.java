package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.module.system.dto.DictOption;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 字典下拉选项的缓存：Redis 里 dict:{typeCode} → 选项列表的 JSON。
 * 读：先查缓存，没有再查库并放进缓存（Cache-Aside）。写：改了字典数据，事务提交以后删掉缓存，下次读时重建
 */
@Component
@RequiredArgsConstructor
public class DictCache {

    private static final String KEY_PREFIX = "dict:";
    /** 兜底的过期时间：万一哪里漏删了，最多一天后也会自己更新 */
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    /** 缓存里有就返回（空列表也算有），没有返回 null */
    public List<DictOption> get(String typeCode) {
        String json = redis.opsForValue().get(KEY_PREFIX + typeCode);
        return json == null ? null : jsonMapper.readValue(json, new TypeReference<List<DictOption>>() {});
    }

    public void put(String typeCode, List<DictOption> options) {
        redis.opsForValue().set(KEY_PREFIX + typeCode, jsonMapper.writeValueAsString(options), TTL);
    }

    /**
     * 删缓存，但等数据库事务提交以后再删。
     * 提交前就删的话，另一个请求可能在这个空档里读到旧数据、又放回缓存，旧数据就一直留着了
     */
    public void evictAfterCommit(String... typeCodes) {
        List<String> keys = Arrays.stream(typeCodes).filter(Objects::nonNull).distinct()
                .map(code -> KEY_PREFIX + code).toList();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.delete(keys);
                }
            });
        } else {
            redis.delete(keys);
        }
    }
}
