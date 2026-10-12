package com.lingmao.scm.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private LoginSessionStore loginSessionStore;

    @InjectMocks
    private JwtAuthFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：token 验签通过，Redis 里也有这一笔 → 认出是 1 号用户 */
    @Test
    void validTokenWithSession_authenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(1L);

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(new LoginUser(1L, "jti-1"));
    }

    /** 规则：token 本身没问题，但 Redis 里那一笔已经删了（退出过）→ 认不出 */
    @Test
    void loggedOutToken_notAuthenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(null);

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** 规则：伪造或过期的 token → 认不出，不去查 Redis，请求照样往下走 */
    @Test
    void invalidToken_notAuthenticatedButPassedOn() throws Exception {
        when(jwtUtil.parse("bad-token")).thenThrow(new JwtException("签名不对"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestWithToken("bad-token"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(loginSessionStore, never()).getUserId(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}