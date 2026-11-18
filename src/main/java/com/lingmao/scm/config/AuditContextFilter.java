package com.lingmao.scm.config;

import com.lingmao.scm.common.log.AuditContext;
import com.lingmao.scm.common.log.TraceIdFilter;
import com.lingmao.scm.common.web.TrustedProxyResolver;
import com.lingmao.scm.security.LoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class AuditContextFilter {
    @Bean FilterRegistrationBean<OncePerRequestFilter> authenticatedAuditContext(TrustedProxyResolver proxy) {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Long actor = LoginUser.currentUserId();
                try (var ignored = AuditContext.open(actor == null ? "" : actor.toString(), proxy.clientIp(request),
                        response.getHeader(TraceIdFilter.REQUEST_HEADER), request.getMethod() + " " + request.getRequestURI())) {
                    chain.doFilter(request, response);
                }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(-85); registration.addUrlPatterns("/api/*");
        return registration;
    }
}
