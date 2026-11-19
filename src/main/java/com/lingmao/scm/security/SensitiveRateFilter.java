package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.common.web.TrustedProxyResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

/** 在认证之后执行；办公室共用一个 IP 时，各登录用户的导出、上传额度不共享。 */
public final class SensitiveRateFilter extends OncePerRequestFilter {
    private final SharedRateLimiter limiter;
    private final TrustedProxyResolver proxy;
    private final JsonMapper json;
    public SensitiveRateFilter(SharedRateLimiter limiter, TrustedProxyResolver proxy, JsonMapper json) {
        this.limiter = limiter; this.proxy = proxy; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String operation = null; int limit = 0; Duration window = Duration.ofMinutes(1);
        if (method.equals("POST") && path.equals("/api/auth/login")) { operation = "login-ip"; limit = 600; window = Duration.ofMinutes(15); }
        else if (method.equals("POST") && path.equals("/api/auth/refresh")) { operation = "refresh"; limit = 120; }
        else if ((method.equals("PUT") && path.equals("/api/auth/password"))
                || (method.equals("POST") && path.matches("/api/users/[1-9][0-9]*/reset-password"))) { operation = "password"; limit = 6; }
        else if (((method.equals("GET") || method.equals("HEAD")) && (path.endsWith("/export")
                || path.matches("/api/inventory-counts/[1-9][0-9]*/sheet")
                || path.matches("/api/tech-packs/[1-9][0-9]*/files/[1-9][0-9]*/preview")
                || path.matches("/api/export-jobs/[1-9][0-9]*/file")))
                || (method.equals("POST") && (path.equals("/api/export-jobs/stock-logs")
                || path.matches("/api/export-jobs/[1-9][0-9]*/retry")))) { operation = "export"; limit = 12; }
        else if (method.equals("POST") && (path.equals("/api/files")
                || path.matches("/api/(materials|styles|suppliers|orders)/import")
                || path.matches("/api/inventory-counts/[1-9][0-9]*/import")
                || path.matches("/api/tech-packs/[1-9][0-9]*/bom-import"))) { operation = "upload"; limit = 60; }
        if (operation != null) {
            Long user = LoginUser.currentUserId();
            String bucket = operation + ":" + (user == null ? "anonymous" : user) + ":" + proxy.clientIp(request);
            long retry;
            try { retry = limiter.acquire(bucket, limit, window); }
            catch (SecurityStateUnavailable unavailable) {
                response.setStatus(503); response.setHeader("Retry-After", "3");
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), R.fail(503, unavailable.getMessage())); return;
            }
            if (retry > 0) {
                response.setStatus(429); response.setHeader("Retry-After", Long.toString(retry));
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), R.fail(429, "操作过于频繁，请稍后重试")); return;
            }
        }
        chain.doFilter(request, response);
    }
}
