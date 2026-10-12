package com.lingmao.scm.config;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtAuthFilter;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final JsonMapper jsonMapper;
    private final PermissionService permissionService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 上传的文件：<img> 标签不会带 token，所以不要求登录；文件名是随机 UUID，猜不到
                        .requestMatchers("/files/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(this::writeUnauthorized))
                .addFilterBefore(new JwtAuthFilter(jwtUtil, loginSessionStore, permissionService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 没登录就访问需要登录的接口：和其他错误一样，返回 R，code 是 401 */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   AuthenticationException e) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        jsonMapper.writeValue(response.getWriter(), R.fail(401, "未登录或登录已过期"));
    }
}