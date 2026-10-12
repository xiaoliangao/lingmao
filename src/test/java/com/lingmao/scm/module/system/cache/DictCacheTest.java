package com.lingmao.scm.module.system.cache;

import com.lingmao.scm.module.system.dto.DictOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictCacheTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    private DictCache dictCache;

    @BeforeEach
    void setUp() {
        dictCache = new DictCache(redis, JsonMapper.builder().build());
    }

    /** 规则：存进去的 JSON 能原样读回来；key 是 dict:类型编码，24 小时过期 */
    @Test
    void putThenGet_roundTrip() {
        when(redis.opsForValue()).thenReturn(ops);
        List<DictOption> options = List.of(new DictOption("春季", "1"), new DictOption("夏季", "2"));

        dictCache.put("season", options);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("dict:season"), json.capture(), eq(Duration.ofHours(24)));
        assertThat(json.getValue()).isEqualTo("[{\"label\":\"春季\",\"value\":\"1\"},{\"label\":\"夏季\",\"value\":\"2\"}]");

        when(ops.get("dict:season")).thenReturn(json.getValue());
        assertThat(dictCache.get("season")).isEqualTo(options);
    }

    /** 规则：不在事务里时立即删；同一个类型编码只删一次，null 忽略 */
    @Test
    void evictAfterCommit_noTransaction_deletesNow() {
        dictCache.evictAfterCommit("season", "season", null);

        verify(redis).delete(List.of("dict:season"));
    }
}
