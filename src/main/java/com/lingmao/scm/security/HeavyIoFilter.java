package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/** 在解析 multipart、建 POI 或 PDF 对象、进 controller 事务之前做准入。 */
public final class HeavyIoFilter extends OncePerRequestFilter {
    private final Semaphore permits = new Semaphore(2);
    private final JsonMapper json;
    public HeavyIoFilter(JsonMapper json) { this.json = json; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !((request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) && (path.endsWith("/export") || path.endsWith("/pdf")
                || path.endsWith("/import-template") || path.matches("/api/inventory-counts/[1-9][0-9]*/sheet")))
                && !(request.getMethod().equals("POST") && (path.equals("/api/files") || path.matches("/api/(materials|styles|suppliers|orders)/import")
                || path.matches("/api/inventory-counts/[1-9][0-9]*/import")));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!permits.tryAcquire()) {
            response.setStatus(429); response.setHeader("Retry-After", "3");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), R.fail(429, "已有文件正在处理，请稍后重试"));
            return;
        }
        try { chain.doFilter(request, response); }
        finally { permits.release(); }
    }
}
