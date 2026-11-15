package com.lingmao.scm.module.style.service.impl;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 款式的权限码（V16 菜单）和"当前登录的人有没有某个码"。接口本身由 @PreAuthorize 拦；
 * 这里给 Service 算详情页的按钮（架构 §4.2），读的是 JwtAuthFilter 放进 SecurityContext 的权限
 */
final class StylePerms {

    static final String LIST = "style:style:list";
    static final String ADD = "style:style:add";
    static final String EDIT = "style:style:edit";
    static final String REMOVE = "style:style:remove";
    static final String IMPORT = "style:style:import";
    static final String EXPORT = "style:style:export";

    private StylePerms() {
    }

    /** 当前登录的人有没有这个权限码；没登录（定时任务）一律没有 */
    static boolean has(String perm) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (perm.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
