package com.lingmao.scm.security;

import com.lingmao.scm.common.auth.CallerScope;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.json.JsonMapper;

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

    @Mock
    private AiSwitchService aiSwitchService;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtUtil, loginSessionStore, permissionService, aiSwitchService, JsonMapper.builder().build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：token 验签通过，Redis 里也有这一笔，用户可用 → 认出是 1 号用户，带上他的权限 */
    @Test
    void validTokenWithSession_authenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.lookup("jti-1")).thenReturn(new LoginSessionStore.Session(1L, null));
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
        when(loginSessionStore.lookup("jti-1")).thenReturn(new LoginSessionStore.Session(1L, null));
        when(permissionService.loadPermissions(1L)).thenReturn(null);

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
        when(loginSessionStore.lookup("jti-1")).thenReturn(new LoginSessionStore.Session(1L, null));
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
        when(loginSessionStore.lookup("jti-1")).thenReturn(null);

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
        verify(loginSessionStore, never()).lookup(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    // ------------------------------------------------------------ 助手令牌（RFC 017 §3.1、§3.7）

    /** 规则：助手令牌、AI 功能开着、有使用助手的权限、GET → 认出来，LoginUser 带 scope = ASSISTANT */
    @Test
    void assistantGet_authenticatedWithScope() throws Exception {
        assistantToken();
        when(aiSwitchService.enabled()).thenReturn(true);
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of(JwtAuthFilter.ASSISTANT_PERM, "purchase:order:list"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(assistantRequest("GET"), new MockHttpServletResponse(), chain);

        LoginUser user = (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(user.assistant()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .as("文件签名靠这个认出是助手（common 不能依赖 security 包）").contains(CallerScope.ASSISTANT_AUTHORITY);
        assertThat(chain.getRequest()).isNotNull();
    }

    /** 规则：AI 功能关着 → 还没过期的助手令牌也回 403，请求不往下走 */
    @Test
    void assistantWhileAiOff_forbidden() throws Exception {
        assistantToken();
        when(aiSwitchService.enabled()).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(assistantRequest("GET"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AI 功能没有打开");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** 规则：助手令牌只读，非 GET 一律 403，不查权限 */
    @Test
    void assistantPost_forbidden() throws Exception {
        assistantToken();
        when(aiSwitchService.enabled()).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(assistantRequest("POST"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        verify(permissionService, never()).loadPermissions(any());
    }

    /** 规则：管理员在角色里收回了"使用智能助手"→ 下一个请求就 403 */
    @Test
    void assistantWithoutPermission_forbidden() throws Exception {
        assistantToken();
        when(aiSwitchService.enabled()).thenReturn(true);
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("purchase:order:list"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(assistantRequest("GET"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** 规则：登记的是助手令牌、令牌里却没写 scope（对不上）→ 当成没登录，不当成普通登录放过 */
    @Test
    void scopeMismatch_notAuthenticated() throws Exception {
        when(jwtUtil.parse("good-token")).thenReturn(Jwts.claims().id("jti-1").subject("1").build());
        when(loginSessionStore.lookup("jti-1")).thenReturn(new LoginSessionStore.Session(1L, LoginUser.SCOPE_ASSISTANT));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestWithToken("good-token"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        verify(aiSwitchService, never()).enabled();
    }

    private void assistantToken() {
        when(jwtUtil.parse("assist-token")).thenReturn(Jwts.claims().id("jti-a").subject("1")
                .add(JwtUtil.SCOPE_CLAIM, LoginUser.SCOPE_ASSISTANT).build());
        when(loginSessionStore.lookup("jti-a")).thenReturn(new LoginSessionStore.Session(1L, LoginUser.SCOPE_ASSISTANT));
    }

    private MockHttpServletRequest assistantRequest(String method) {
        MockHttpServletRequest request = requestWithToken("assist-token");
        request.setMethod(method);
        return request;
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}