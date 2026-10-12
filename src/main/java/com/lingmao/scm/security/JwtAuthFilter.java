package com.lingmao.scm.security;

import io.jsonwebtoken.Claims;
import com.lingmao.scm.module.system.service.PermissionService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** 每个请求先经过这里：带了有效的 token，就告诉 Spring Security“这个请求是谁发的” */
public class JwtAuthFilter extends OncePerRequestFilter { //OncePerRequestFilter是Spring提供的父类过滤器，保证每个请求只经过过滤器一次

    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final PermissionService permissionService;

    public JwtAuthFilter(JwtUtil jwtUtil, LoginSessionStore loginSessionStore, PermissionService permissionService) {
        this.jwtUtil = jwtUtil;
        this.loginSessionStore = loginSessionStore;
        this.permissionService = permissionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            authenticate(header.substring(PREFIX.length()));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }
        Long userId = loginSessionStore.getUserId(claims.getId());
        if (userId == null) {
            return;
        }
        // 每个请求都从库里查一次：账号停用、删除，或者权限改了，下一个请求立即生效
        Set<String> perms = permissionService.loadPermissions(userId);
        if (perms == null) {
            return;
        }
        List<SimpleGrantedAuthority> authorities = perms.stream().map(SimpleGrantedAuthority::new).toList();
        LoginUser loginUser = new LoginUser(userId, claims.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities));
    }
}