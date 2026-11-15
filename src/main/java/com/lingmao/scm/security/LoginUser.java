package com.lingmao.scm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 当前登录的是谁：用户 id，以及这次登录的 token 编号（退出时要用） */
public record LoginUser(Long userId, String jti) {
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
}
