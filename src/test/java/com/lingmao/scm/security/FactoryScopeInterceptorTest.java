package com.lingmao.scm.security;

import com.lingmao.scm.common.auth.CallerScope;
import com.lingmao.scm.common.result.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactoryScopeInterceptorTest {

    @SuppressWarnings("unused")
    static class Samples {
        @FactoryAccess
        public R<String> report() { return R.ok("x"); }
        public R<String> userOptions() { return R.ok("x"); }
    }

    private final FactoryScopeInterceptor interceptor = new FactoryScopeInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 规则（RFC 019 §7）：工厂账号只放标了 @FactoryAccess 的接口和带签名的文件，别的（包括不要权限码的）一律 403；内部账号不受影响 */
    @Test
    void preHandle_factoryOnlyAnnotated() throws Exception {
        login(List.of("production:order:list"));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("userOptions")))
                .as("内部账号照常").isTrue();

        login(List.of(CallerScope.FACTORY_AUTHORITY, CallerScope.factory(12)));
        assertThat(CallerScope.factoryId()).isEqualTo(12L);
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("report"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new ResourceHttpRequestHandler()))
                .as("/files/** 靠签名").isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("userOptions")))
                .isInstanceOf(FactoryDenied.class);
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .as("不认识的处理器也不放").isInstanceOf(FactoryDenied.class);
    }

    /** 规则：工厂标识只从 PermissionService 给的 scope:FACTORY:&lt;id&gt; 读；格式不对的当不是工厂 */
    @Test
    void factoryOf_parsesOnlyTheReservedMarker() {
        assertThat(CallerScope.factoryOf(Set.of("production:order:list", CallerScope.factory(7)))).isEqualTo(7L);
        assertThat(CallerScope.factoryOf(Set.of("production:order:list"))).isNull();
        assertThat(CallerScope.factoryOf(Set.of(CallerScope.FACTORY_PREFIX + "abc"))).isNull();
    }

    private static HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new Samples(), Samples.class.getMethod(name));
    }

    private static void login(List<String> authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "j"), null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
