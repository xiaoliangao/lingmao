package com.lingmao.scm.common.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 这次请求是谁替人发的（RFC 017 §3.1）。拿助手令牌来的请求，JwtAuthFilter 在权限里多放一个 {@link #ASSISTANT_AUTHORITY}。
 * 放在 common 里，是因为 common 的工具（文件签名）也要知道，而 common 不能依赖 security 包（ArchitectureTest 规则 5）
 */
public final class CallerScope {
    /** 助手令牌的请求带的权限标识。不在 sys_menu 里，角色勾不到 */
    public static final String ASSISTANT_AUTHORITY = "scope:ASSISTANT";

    private CallerScope() {
    }

    /** 当前请求是不是拿助手令牌来的；没登录、不在请求里返回 false */
    public static boolean assistant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (ASSISTANT_AUTHORITY.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
