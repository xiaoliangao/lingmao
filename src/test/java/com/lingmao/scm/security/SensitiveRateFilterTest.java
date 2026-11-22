package com.lingmao.scm.security;

import com.lingmao.scm.common.web.TrustedProxyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveRateFilterTest {

    /** 记下每次拿的额度（桶名 / 上限 / 窗口秒数）；exhausted 里的操作回"还要等 7 秒"，down 为真时限流状态读不到 */
    private final List<String> buckets = new ArrayList<>();
    private final Set<String> exhausted = new HashSet<>();
    private boolean down;
    private final SharedRateLimiter limiter = (bucket, limit, window) -> {
        if (down) throw new SecurityStateUnavailable(new RuntimeException("redis down"));
        buckets.add(bucket + "/" + limit + "/" + window.toSeconds());
        return exhausted.contains(bucket.substring(0, bucket.indexOf(':'))) ? 7 : 0;
    };
    private final TrustedProxyResolver proxy = mock(TrustedProxyResolver.class);
    private final SensitiveRateFilter filter = new SensitiveRateFilter(limiter, proxy, JsonMapper.builder().build());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则：助手令牌调导出、预览这种有专门额度的接口，专门额度（12 次）和助手额度（120 次）都要算，不会因为是助手就放宽；
     * 助手的桶和这个人自己的分开记，助手打满了不连累他在页面上导出
     */
    @Test
    void assistantOnHeavyEndpoint_bothBucketsCountedSeparatelyFromUser() throws Exception {
        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        filter.doFilter(get("/api/tech-packs/1/files/2/preview"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).containsExactly("export:1~assistant:127.0.0.1/12/60", "assistant:1~assistant:127.0.0.1/120/60");

        buckets.clear();
        as(new LoginUser(1L, "j"));
        filter.doFilter(get("/api/materials/export"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).as("普通登录只算导出额度，桶是他自己的").containsExactly("export:1:127.0.0.1/12/60");
    }

    /** 规则：普通登录查列表不限流；助手令牌查列表只算助手额度 */
    @Test
    void plainGet_onlyAssistantCounted() throws Exception {
        as(new LoginUser(1L, "j"));
        filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).isEmpty();

        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).containsExactly("assistant:1~assistant:127.0.0.1/120/60");
    }

    /** 规则：专门额度超了回 429 和 Retry-After，不往下走，也不再扣助手额度；助手额度超了一样 429 */
    @Test
    void exhausted_429AndStops() throws Exception {
        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        exhausted.add("export");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get("/api/materials/export"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        assertThat(chain.getRequest()).as("没往下走").isNull();
        assertThat(buckets).hasSize(1);

        exhausted.clear();
        exhausted.add("assistant");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        filter.doFilter(get("/api/orders"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }

    /** 规则：限流状态读不到（Redis 出错）回 503，不放行 */
    @Test
    void limiterDown_503() throws Exception {
        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        down = true;
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get("/api/orders"), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    /** 规则：换助手令牌每人每分钟 30 次 */
    @Test
    void exchange_ownBucket() throws Exception {
        as(new LoginUser(1L, "j"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/assistant-token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).containsExactly("assistant-token:1:127.0.0.1/30/" + Duration.ofMinutes(1).toSeconds());
    }

    private void as(LoginUser user) {
        when(proxy.clientIp(any())).thenReturn("127.0.0.1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
