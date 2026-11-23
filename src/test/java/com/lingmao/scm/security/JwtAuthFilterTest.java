package com.lingmao.scm.security;

import com.lingmao.scm.module.system.service.PermissionService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private JwtAuthFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：token 验签通过，Redis 里也有这一笔，用户可用 → 认出是 1 号用户，带上他的权限 */
    @Test
    void validTokenWithSession_authenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(1L);
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("system:user:list"));

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(new LoginUser(1L, "jti-1"));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("system:user:list");
    }

    /** 规则：token 和会话都没问题，但账号已经停用或删除 → 认不出（不用等 token 过期） */
    @Test
    void disabledUser_notAuthenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(1L);
        when(permissionService.loadPermissions(1L)).thenReturn(null);

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** 两次查询之间授权已经提交：旧会话不能携带这次读到的新权限进入 Controller。 */
    @Test
    void grantChangesDuringPermissionLookup_doNotAuthenticateOldSession() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(1L, null);
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("system:user:list"));

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * 规则：查权限时数据库出错 → 异常抛出去，不当成"没登录"（那样前端会以为登录过期、把人踢回登录页）。
     * 抛出去以后由 TraceIdFilter 回 500 和错误编号
     */
    @Test
    void permissionLookupFails_exceptionPropagates() {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.getUserId("jti-1")).thenReturn(1L);
        when(permissionService.loadPermissions(1L)).thenThrow(new CannotGetJdbcConnectionException("数据库连不上"));
        MockFilterChain chain = new MockFilterChain();

        assertThatThrownBy(() -> filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), chain))
                .isInstanceOf(CannotGetJdbcConnectionException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNull();
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
