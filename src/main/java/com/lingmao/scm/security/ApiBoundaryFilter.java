package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.common.web.TrustedProxyResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ApiBoundaryFilter extends OncePerRequestFilter {
    private static final long JSON_LIMIT = 2L * 1024 * 1024;
    private final DeploymentNamespace namespace;
    private final TrustedProxyResolver proxy;
    private final JsonMapper json;
    public ApiBoundaryFilter(DeploymentNamespace namespace, TrustedProxyResolver proxy, JsonMapper json) {
        this.namespace = namespace; this.proxy = proxy; this.json = json;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = response.getHeader("X-Request-Id");
        if (trace == null) trace = response.getHeader("X-Trace-Id");
        if (trace == null) trace = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", trace); response.setHeader("X-Trace-Id", trace);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        if (proxy.secure(request)) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        String tenant = request.getHeader("X-Tenant-Id");
        if (tenant != null && !namespace.tenant().equals(tenant)) {
            reject(response, 403, "当前部署不提供所请求租户的数据"); return;
        }
        boolean jsonBody = request.getContentType() != null && request.getContentType().toLowerCase(java.util.Locale.ROOT).contains("json");
        if (jsonBody && request.getContentLengthLong() > JSON_LIMIT) {
            reject(response, 413, "请求内容超过 2MB，请分批提交"); return;
        }
        boolean secure = proxy.secure(request);
        int publicPort = publicPort(request, secure);
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;
            @Override public boolean isSecure() { return secure; }
            @Override public String getScheme() { return secure ? "https" : super.getScheme(); }
            @Override public int getServerPort() { return publicPort; }
            @Override public ServletInputStream getInputStream() throws IOException {
                if (!jsonBody) return super.getInputStream();
                if (bounded == null) bounded = limited(super.getInputStream());
                return bounded;
            }
            @Override public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
        chain.doFilter(wrapped, response);
    }

    private static int publicPort(HttpServletRequest request, boolean secure) {
        if (!secure || request.isSecure()) return request.getServerPort();
        // 受信网关保留 Host，含非默认的对外 HTTPS 端口。
        // 不接受客户端另传的 X-Forwarded-Port、X-Forwarded-Host。
        String host = request.getHeader("Host");
        if (host != null) {
            try {
                java.net.URI authority = java.net.URI.create("https://" + host);
                int port = authority.getPort();
                if (authority.getHost() != null && authority.getRawUserInfo() == null
                        && authority.getRawPath().isEmpty() && authority.getRawQuery() == null
                        && authority.getRawFragment() == null && port > 0 && port <= 65535) return port;
            } catch (IllegalArgumentException ignored) { /* Invalid Host cannot establish a custom public port. */ }
        }
        return 443;
    }

    private static ServletInputStream limited(ServletInputStream input) {
        return new ServletInputStream() {
            long consumed;
            private int count(int n) throws RequestBodyTooLarge {
                if (n > 0 && (consumed += n) > JSON_LIMIT) throw new RequestBodyTooLarge();
                return n;
            }
            @Override public int read() throws IOException { int n = input.read(); if (n >= 0) count(1); return n; }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                return count(input.read(buffer, offset, (int) Math.min(length, JSON_LIMIT - consumed + 1)));
            }
            @Override public boolean isFinished() { return input.isFinished(); }
            @Override public boolean isReady() { return input.isReady(); }
            @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
            @Override public void close() throws IOException { input.close(); }
        };
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), R.fail(status, message));
    }
}
