package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;

import java.io.OutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantScopeInterceptorTest {

    /** 几种接口方法的样子：只有第一个让助手调 */
    @SuppressWarnings("unused")
    static class Samples {
        public R<String> data() { return R.ok("x"); }
        public ResponseEntity<byte[]> export() { return null; }
        public R<Void> writesResponse(HttpServletResponse response) { return null; }
        public R<Void> writesStream(OutputStream out) { return null; }
        public R<Void> webRequest(ServletWebRequest request) { return null; }
        @NoAssistant
        public R<String> bank() { return R.ok("x"); }
    }

    private final AssistantScopeInterceptor interceptor = new AssistantScopeInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：只放返回 R、参数里不拿响应对象、没标 @NoAssistant 的接口方法；静态文件这种不是接口方法的也不放 */
    @Test
    void allowed_onlyPlainDataMethods() throws Exception {
        assertThat(AssistantScopeInterceptor.allowed(handler("data"))).isTrue();
        assertThat(AssistantScopeInterceptor.allowed(handler("export"))).as("返回文件").isFalse();
        assertThat(AssistantScopeInterceptor.allowed(handler("writesResponse"))).as("自己写响应").isFalse();
        assertThat(AssistantScopeInterceptor.allowed(handler(Samples.class.getMethod("writesStream", OutputStream.class)))).as("拿输出流").isFalse();
        assertThat(AssistantScopeInterceptor.allowed(handler(Samples.class.getMethod("webRequest", ServletWebRequest.class)))).as("WebRequest 能取到响应").isFalse();
        assertThat(AssistantScopeInterceptor.allowed(handler("bank"))).as("@NoAssistant").isFalse();
        assertThat(AssistantScopeInterceptor.allowed(new Object())).as("不是接口方法").isFalse();
    }

    /** 规则：只对助手令牌的请求生效；普通登录调导出照常放行 */
    @Test
    void preHandle_onlyForAssistantToken() throws Exception {
        login(new LoginUser(1L, "j"));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("export"))).isTrue();

        login(new LoginUser(1L, "a", LoginUser.SCOPE_ASSISTANT));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("data"))).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("export")))
                .isInstanceOf(AssistantDenied.class);
    }

    private static HandlerMethod handler(String name) throws Exception {
        return name.equals("writesResponse")
                ? handler(Samples.class.getMethod(name, HttpServletResponse.class))
                : handler(Samples.class.getMethod(name));
    }

    private static HandlerMethod handler(java.lang.reflect.Method method) {
        return new HandlerMethod(new Samples(), method);
    }

    private static void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
