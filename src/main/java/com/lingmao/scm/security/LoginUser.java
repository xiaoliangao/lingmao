package com.lingmao.scm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 当前登录的是谁：用户 id，以及这次登录的 token 编号（退出时要用） */
public record LoginUser(Long userId, String jti, String scope) {

    /** 助手令牌（RFC 017 §3.1）：用户打开助手时换来的，30 分钟、只读、不能刷新 */
    public static final String SCOPE_ASSISTANT = "ASSISTANT";

    /** 普通登录（浏览器）：没有 scope */
    public LoginUser(Long userId, String jti) {
        this(userId, jti, null);
    }

    /** 这次请求是不是经助手来的 */
    public boolean assistant() {
        return SCOPE_ASSISTANT.equals(scope);
    }

    /**
     * record 是什么：Java 16 起有的一种写法，专门用来定义"只装数据、创建以后不改"的类。这一行等于自动生成了：两个 private final 字段、一个两参数的构造器、两个读取方法 userId() 和 jti()（注意没有 get 前缀），还有 equals、toString。
     *
     * 为什么这里用 record 不用 @Data 类：LoginUser 在过滤器里创建，之后只读、不改，正好是 record 的用途。实体和 DTO 不用 record，因为框架要先 new 一个空的再一个个 set。
     */

    /**
     * 当前请求是哪个用户发的，JwtAuthFilter 认出来以后放进了 SecurityContext。
     * 没登录、不在请求里（启动时建 admin、以后的定时任务）返回 null。
     * Controller 里用 @AuthenticationPrincipal 拿；拿不到参数的地方（审计列自动填充、操作记录）用这个
     */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser user ? user.userId() : null;
    }

    /** 当前请求是不是拿助手令牌来的（RFC 017 §3.1）。没登录返回 false */
    public static boolean currentIsAssistant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser user && user.assistant();
    }

    /**
     * 当前用户有没有这个权限标识（菜单、按钮的权限码，或者 data:all）：Service 里要按权限分支时用（多级审批的第 1 级、
     * 详情的 actions、数据范围），和 @PreAuthorize("hasAuthority(...)") 看的是同一份。
     * 权限是 JwtAuthFilter 每个请求从库里现取的，收回权限下一个请求就生效。没登录返回 false
     */
    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser)) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (granted.getAuthority().equals(authority)) {
                return true;
            }
        }
        return false;
    }
}
