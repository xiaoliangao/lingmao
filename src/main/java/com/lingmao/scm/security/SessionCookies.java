package com.lingmao.scm.security;

import com.lingmao.scm.common.web.TrustedProxyResolver;
import com.lingmao.scm.module.system.dto.AuthTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookies {
    public static final String REFRESH = "scm_refresh";
    public static final String BROWSER = "scm_browser";
    private final TrustedProxyResolver proxy;

    public SessionCookies(TrustedProxyResolver proxy) { this.proxy = proxy; }

    public void write(HttpServletRequest request, HttpServletResponse response, AuthTokens tokens) {
        set(request, response, REFRESH, tokens.refreshToken(), tokens.maxAgeSeconds());
        set(request, response, BROWSER, tokens.browserToken(), tokens.maxAgeSeconds());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        set(request, response, REFRESH, "", 0);
        set(request, response, BROWSER, "", 0);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) {
            if (value != null) return null; // Ambiguous cookie paths must not select an attacker-controlled value.
            value = cookie.getValue();
        }
        return value;
    }

    public void requireRefreshHeader(HttpServletRequest request) {
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) throw new AuthRejected();
        // 跨源带凭据的请求还要另外满足明确配置的 CORS 策略。
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) throw new AuthRejected();
    }

    private void set(HttpServletRequest request, HttpServletResponse response, String name, String value, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).path("/api/auth")
                .httpOnly(true).sameSite("Strict").secure(proxy.secure(request)).maxAge(seconds).build().toString());
    }
}
