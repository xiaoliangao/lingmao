package com.lingmao.scm.config;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.common.web.TrustedProxyResolver;
import com.lingmao.scm.security.ApiBoundaryFilter;
import com.lingmao.scm.security.SensitiveRateFilter;
import com.lingmao.scm.security.SharedRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityBoundaryConfig {
    @Bean FilterRegistrationBean<ApiBoundaryFilter> apiBoundary(DeploymentNamespace namespace, TrustedProxyResolver proxy, JsonMapper json) {
        var registration = new FilterRegistrationBean<>(new ApiBoundaryFilter(namespace, proxy, json));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1); registration.addUrlPatterns("/api/*", "/files/*");
        return registration;
    }

    @Bean FilterRegistrationBean<CorsFilter> strictCors(@Value("${lingmao.cors.allowed-origins:}") String configured) {
        List<String> origins = Arrays.stream(configured.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (origins.stream().anyMatch(o -> o.contains("*") || !o.matches("https?://[A-Za-z0-9.\\-\\[\\]:]+")))
            throw new IllegalArgumentException("CORS origins must be explicit http(s) origins without wildcards");
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins); cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Company-Id", "X-Tenant-Id",
                "X-Request-Id", "X-Trace-Id", "X-Requested-With"));
        cors.setExposedHeaders(List.of("Content-Disposition", "X-Request-Id", "X-Trace-Id", "Retry-After", "Location"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**", cors);
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean FilterRegistrationBean<SensitiveRateFilter> sensitiveRate(SharedRateLimiter limiter, TrustedProxyResolver proxy, JsonMapper json) {
        var registration = new FilterRegistrationBean<>(new SensitiveRateFilter(limiter, proxy, json));
        registration.setOrder(-90); registration.addUrlPatterns("/api/*");
        return registration;
    }

    @Bean FilterRegistrationBean<com.lingmao.scm.security.HeavyIoFilter> heavyIo(JsonMapper json) {
        var registration = new FilterRegistrationBean<>(new com.lingmao.scm.security.HeavyIoFilter(json));
        registration.setOrder(-89); registration.addUrlPatterns("/api/*");
        return registration;
    }
}
