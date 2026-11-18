package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictCacheTest {

    @Mock
    private SharedCache shared;

    @Mock
    private OutboxService outbox;

    private DictCache dictCache;
    private final DeploymentNamespace namespace = new DeploymentNamespace("unit-test");

    @BeforeEach
    void setUp() {
        dictCache = new DictCache(JsonMapper.builder().build(), shared, namespace, outbox);
    }

    /** JSON 往返保留停用状态；实际读写只走有租户前缀的共享缓存及正/负 TTL 契约。 */
    @Test
    void putThenGet_roundTrip() {
        List<DictOption> options = List.of(new DictOption("春季", "1", 1), new DictOption("夏季", "2", 0));
        AtomicReference<String> encoded = new AtomicReference<>();
        when(shared.getOrLoad(eq(namespace.sharedKey("dict-v3", "season")), eq(Duration.ofHours(24)),
                eq(Duration.ofSeconds(30)), any())).thenAnswer(inv -> {
            String value = inv.<Supplier<String>>getArgument(3).get();
            encoded.set(value);
            return value;
        });

        assertThat(dictCache.getOrLoad("season", () -> options)).isEqualTo(options);
        assertThat(encoded.get()).isEqualTo(
                "[{\"label\":\"春季\",\"value\":\"1\",\"status\":1},{\"label\":\"夏季\",\"value\":\"2\",\"status\":0}]");
    }

    /** 规则：不在事务里时立即删；同一个类型编码只删一次，null 忽略 */
    @Test
    void evictAfterCommit_noTransaction_deletesNow() {
        dictCache.evictAfterCommit("season", "season", null);

        verify(shared).invalidate(namespace.sharedKey("dict-v3", "season"));
    }

    /** 规则：启动时逐个类型删，不进发件箱；Redis 连不上不抛（不挡启动），剩下的也不再试 */
    @Test
    void evictNow_deletesEachType_redisDownDoesNotThrow() {
        dictCache.evictNow(List.of("season", "style_class"));
        verify(shared).invalidate(namespace.sharedKey("dict-v3", "season"));
        verify(shared).invalidate(namespace.sharedKey("dict-v3", "style_class"));
        verifyNoInteractions(outbox);

        doThrow(new RedisConnectionFailureException("down")).when(shared).invalidate(namespace.sharedKey("dict-v3", "brand"));
        assertThatCode(() -> dictCache.evictNow(List.of("brand", "unit"))).doesNotThrowAnyException();
        verify(shared, never()).invalidate(namespace.sharedKey("dict-v3", "unit"));
    }
}
