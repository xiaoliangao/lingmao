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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveRateFilterTest {

    private final List<String> buckets = new ArrayList<>();
    private final SharedRateLimiter limiter = (bucket, limit, window) -> {
        buckets.add(bucket.substring(0, bucket.indexOf(':')) + "/" + limit);
        return 0;
    };
    private final TrustedProxyResolver proxy = mock(TrustedProxyResolver.class);
    private final SensitiveRateFilter filter = new SensitiveRateFilter(limiter, proxy, JsonMapper.builder().build());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：助手令牌调导出、预览这种有专门额度的接口，专门额度（12 次）和助手额度（120 次）都要算，不会因为是助手就放宽 */
    @Test
    void assistantOnHeavyEndpoint_bothBucketsCounted() throws Exception {
        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));

        filter.doFilter(get("/api/tech-packs/1/files/2/preview"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(buckets).containsExactly("export/12", "assistant/120");
    }

    /** 规则：普通登录查列表不限流；助手令牌查列表只算助手额度 */
    @Test
    void plainGet_onlyAssistantCounted() throws Exception {
        as(new LoginUser(1L, "j"));
        filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).isEmpty();

        as(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(buckets).containsExactly("assistant/120");
    }

    private void as(LoginUser user) {
        when(proxy.clientIp(any())).thenReturn("127.0.0.1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
