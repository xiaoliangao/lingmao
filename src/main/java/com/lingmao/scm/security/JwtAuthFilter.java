package com.lingmao.scm.security;

import com.lingmao.scm.common.auth.CallerScope;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 每个请求先经过这里：带了有效的 token，就告诉 Spring Security“这个请求是谁发的” */
public class JwtAuthFilter extends OncePerRequestFilter { //OncePerRequestFilter是Spring提供的父类过滤器，保证每个请求只经过过滤器一次

    private static final String PREFIX = "Bearer ";
    /** 使用智能助手的权限（V54）。换助手令牌要它，助手令牌的每个请求也要它：收回权限下一个请求就不能用 */
    public static final String ASSISTANT_PERM = "assistant:chat:use";

    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final PermissionService permissionService;
    private final AiSwitchService aiSwitchService;
    private final JsonMapper json;

    public JwtAuthFilter(JwtUtil jwtUtil, LoginSessionStore loginSessionStore, PermissionService permissionService,
                         AiSwitchService aiSwitchService, JsonMapper json) {
        this.jwtUtil = jwtUtil;
        this.loginSessionStore = loginSessionStore;
        this.permissionService = permissionService;
        this.aiSwitchService = aiSwitchService;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            String denied = authenticate(header.substring(PREFIX.length()), request.getMethod());
            if (denied != null) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), R.fail(403, denied));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 认出是谁，放进 SecurityContext。认不出（验签失败、过期、退出过、改过密、停用）什么都不放，后面按没登录回 401。
     * 助手令牌（RFC 017 §3.1、§3.7）认出来了但这个请求不让过时，返回原因，回 403：
     * AI 功能关着（包括关之前发出去、还没过期的）、不是 GET、没有使用助手的权限。
     * 返回文件的 GET（导出、下载、模板）由 AssistantScopeInterceptor 拦，这里还不知道是哪个接口
     */
    private String authenticate(String token, String method) {
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
        LoginSessionStore.Session session = loginSessionStore.lookup(claims.getId());
        if (session == null) {
            return null;
        }
        // 用途以登记的为准；令牌里写的和登记的对不上、或者是不认识的用途，当成没登录
        String scope = session.scope();
        if (!Objects.equals(scope, claims.get(JwtUtil.SCOPE_CLAIM, String.class))
                || (scope != null && !LoginUser.SCOPE_ASSISTANT.equals(scope))) {
            return null;
        }
        if (scope != null) {
            if (!aiSwitchService.enabled()) {
                return "AI 功能没有打开";
            }
            if (!"GET".equals(method)) {
                return "助手只能查，不能改";
            }
        }
        // 每个请求都从库里查一次：账号停用、删除，或者权限改了，下一个请求立即生效。
        // 查的时候数据库、Redis 出错不在这里接：接住当成"没登录"的话，前端以为登录过期、把人踢回登录页。
        // 让它抛出去，由 TraceIdFilter 记日志、回 500 和错误编号
        Set<String> perms = permissionService.loadPermissions(session.userId());
        if (perms == null) {
            return null;
        }
        if (scope != null && !perms.contains(ASSISTANT_PERM)) {
            return "没有使用智能助手的权限";
        }
        // scope: 开头的是这里按令牌用途放的标识，不认库里的：菜单上就算有人填了同名权限码，普通登录也不会被当成助手
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(perms.stream()
                .filter(p -> !p.startsWith(CallerScope.RESERVED_PREFIX)).map(SimpleGrantedAuthority::new).toList());
        if (scope != null) {
            authorities.add(new SimpleGrantedAuthority(CallerScope.ASSISTANT_AUTHORITY));
        }
        LoginUser loginUser = new LoginUser(session.userId(), claims.getId(), scope);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities));
        return null;
    }
}
