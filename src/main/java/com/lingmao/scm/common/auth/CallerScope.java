package com.lingmao.scm.common.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;

/**
 * 这次请求是谁替人发的（RFC 017 §3.1）。拿助手令牌来的请求，JwtAuthFilter 在权限里多放一个 {@link #ASSISTANT_AUTHORITY}；
 * 工厂账号（RFC 019 §7）的请求放 {@link #FACTORY_AUTHORITY} 和带供应商 id 的 {@link #FACTORY_PREFIX}。
 * 放在 common 里，是因为 common 的工具（文件签名）也要知道，而 common 不能依赖 security 包（ArchitectureTest 规则 5）
 */
public final class CallerScope {
    /** 按令牌用途放的权限标识都以它开头。菜单管理不让权限码用这个开头，JwtAuthFilter 也不认库里这样开头的权限码 */
    public static final String RESERVED_PREFIX = "scope:";
    /** 助手令牌的请求带的权限标识 */
    public static final String ASSISTANT_AUTHORITY = RESERVED_PREFIX + "ASSISTANT";
    /**
     * 这个账号要先改密码（RFC 018 §2）：PermissionService 加载权限时只给这一个，JwtAuthFilter 见了它只放行看自己、改密码、退出。
     * 不会变成请求的权限（以 RESERVED_PREFIX 开头的都不放进去）
     */
    public static final String MUST_CHANGE_PASSWORD = RESERVED_PREFIX + "MUST_CHANGE_PASSWORD";
    /**
     * 工厂账号（RFC 019 §7）：PermissionService 加载权限时只给这一个（后面跟供应商 id，如 scope:FACTORY:12），一个业务权限都没有。
     * JwtAuthFilter 见了它放进请求的权限（和 {@link #FACTORY_AUTHORITY} 一起），FactoryScopeInterceptor 只放标了 @FactoryAccess 的接口
     */
    public static final String FACTORY_PREFIX = RESERVED_PREFIX + "FACTORY:";
    /** 工厂账号的请求都带它：接口上写 hasAuthority('scope:FACTORY') */
    public static final String FACTORY_AUTHORITY = RESERVED_PREFIX + "FACTORY";

    private CallerScope() {
    }

    /** 工厂账号的权限标识：scope:FACTORY:12 */
    public static String factory(long partnerId) {
        return FACTORY_PREFIX + partnerId;
    }

    /** 权限里有没有工厂标识，有就是哪家（供应商 id）；没有、格式不对返回 null */
    public static Long factoryOf(Collection<String> perms) {
        for (String p : perms) {
            if (p.startsWith(FACTORY_PREFIX)) {
                try {
                    return Long.valueOf(p.substring(FACTORY_PREFIX.length()));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 当前请求是不是工厂账号发的，是就返回哪家（供应商 id）；内部账号、没登录、不在请求里返回 null */
    public static Long factoryId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return factoryOf(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
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
