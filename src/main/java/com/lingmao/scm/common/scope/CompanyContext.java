package com.lingmao.scm.common.scope;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;
import java.util.Set;

/** Expected page context only. DataScope and business permissions remain independent authorizations. */
public final class CompanyContext {
    private CompanyContext() { }

    public static Long header(boolean required) {
        HttpServletRequest request = request();
        if (request == null) return null;
        String value = request.getHeader("X-Company-Id");
        if (value == null || value.isBlank()) {
            if (required) throw new BoundaryViolation(400, "缺少单据主体，请刷新当前页面后重试");
            return null;
        }
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new BoundaryViolation(400, "单据主体格式不正确");
        }
    }

    public static void target(Long persisted) {
        HttpServletRequest request = request();
        if (request == null || persisted == null) return;
        Long supplied = header(!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()));
        if (supplied != null && !Objects.equals(supplied, persisted))
            throw new BoundaryViolation(403, "当前页面主体与目标单据不一致，请重新打开单据");
    }

    public static void create(Long company) {
        if (request() == null) return;
        if (!Objects.equals(header(true), company)) throw new BoundaryViolation(403, "当前页面主体与新单据不一致");
    }

    public static void save(Long persisted, Long target, Long expected) {
        if (request() == null) return;
        target(persisted);
        if (expected != null) {
            if (!Objects.equals(expected, persisted)) throw new BoundaryViolation(403, "单据主体已经变化，请刷新后重试");
        } else if (!Objects.equals(persisted, target)) {
            throw new BoundaryViolation(403, "更换草稿主体需要明确原主体，请刷新后重试");
        }
    }

    public static boolean isHttpRequest() { return request() != null; }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest() : null;
    }
}
