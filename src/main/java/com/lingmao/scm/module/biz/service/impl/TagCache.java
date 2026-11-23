package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.dto.TagObjectVO;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.service.TagObjectProvider.Target;
import com.lingmao.scm.module.system.service.CacheService;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 缓存只含最多 100 个选项或 30 个关联，授权始终在它之前实时执行。
 * 稳定 epoch 键失效后换代 payload，改名/归档不需要全库扫描关联或 Redis KEYS。
 * 所有键由可信 namespace 构造；库共享明确使用 company:shared，对象键另含真实公司。
 */
@Component
public class TagCache {
    private static final Duration EPOCH_TTL = Duration.ofHours(24);
    private static final Duration OPTIONS_TTL = Duration.ofMinutes(2);
    private static final Duration OBJECT_TTL = Duration.ofSeconds(30);
    private static final Duration EMPTY_TTL = Duration.ofSeconds(5);
    private final CacheService cache;
    private final DeploymentNamespace namespace;
    private final JsonMapper json;

    public TagCache(CacheService cache, DeploymentNamespace namespace, JsonMapper json) {
        this.cache = cache; this.namespace = namespace; this.json = json;
    }

    public List<TagVO> options(String canonicalPrefix, int size, List<Long> included, Supplier<List<TagVO>> loader) {
        return options("options", canonicalPrefix, size, included, loader);
    }

    /** 历史筛选和活跃选项不能串缓存；改名/归档仍共用同一库 epoch 失效。 */
    public List<TagVO> filterOptions(String canonicalPrefix, int size, List<Long> included, Supplier<List<TagVO>> loader) {
        return options("filter-options", canonicalPrefix, size, included, loader);
    }

    private List<TagVO> options(String kind, String canonicalPrefix, int size, List<Long> included, Supplier<List<TagVO>> loader) {
        String fingerprint = digest(json.writeValueAsString(List.of(canonicalPrefix, size, included)));
        String key = key(kind + ":" + epoch(libraryEpoch()) + ":" + fingerprint);
        String encoded = cache.getOrLoad(key, OPTIONS_TTL, EMPTY_TTL, () -> json.writeValueAsString(loader.get()));
        return json.readValue(encoded, new TypeReference<List<TagVO>>() { });
    }

    public TagObjectVO object(Target target, Supplier<TagObjectVO> loader) {
        String key = key("object:" + identity(target) + ":" + epoch(libraryEpoch()) + ":" + epoch(objectEpoch(target)));
        String encoded = cache.getOrLoad(key, OBJECT_TTL, EMPTY_TTL, () -> {
            TagObjectVO value = loader.get();
            return value.version() == 0 && value.tags().isEmpty() ? "null" : json.writeValueAsString(value);
        });
        return "null".equals(encoded) ? new TagObjectVO(0, List.of()) : json.readValue(encoded, TagObjectVO.class);
    }

    public void objectChanged(Target target, boolean libraryChanged) {
        List<String> keys = new ArrayList<>();
        keys.add(objectEpoch(target));
        if (libraryChanged) keys.add(libraryEpoch());
        cache.evictAfterCommit(keys);
    }

    public void libraryChanged() { cache.evictAfterCommit(List.of(libraryEpoch())); }

    private String epoch(String key) {
        return cache.getOrLoad(key, EPOCH_TTL, EPOCH_TTL, () -> UUID.randomUUID().toString());
    }
    private String libraryEpoch() { return key("library-epoch"); }
    private String objectEpoch(Target target) { return key("object-epoch:" + identity(target)); }
    private String key(String value) { return namespace.sharedKey("public-tags-v1", value); }
    private static String identity(Target target) {
        return target.type().name() + ":" + target.id() + ":company:" + (target.companyId() == null ? "shared" : target.companyId());
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
