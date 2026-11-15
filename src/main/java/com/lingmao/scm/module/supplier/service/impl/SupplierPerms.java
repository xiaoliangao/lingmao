package com.lingmao.scm.module.supplier.service.impl;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 供应商的权限码（RFC 003 §13.1）和"当前登录的人有没有某个码"。
 * 接口本身由 @PreAuthorize 拦；这里给 Service 用：按钮由后端算（架构 §4.2）、没有 bank 的人保存时不收账号（RFC 004 §5）、
 * 导成合作中要 admit（PRD §10）。读的是 JwtAuthFilter 放进 SecurityContext 的权限，和 @PreAuthorize 看的是同一份
 */
final class SupplierPerms {

    static final String LIST = "supplier:supplier:list";
    static final String ADD = "supplier:supplier:add";
    static final String EDIT = "supplier:supplier:edit";
    static final String REMOVE = "supplier:supplier:remove";
    static final String IMPORT = "supplier:supplier:import";
    static final String EXPORT = "supplier:supplier:export";
    static final String ADMIT = "supplier:supplier:admit";
    static final String BANK = "supplier:supplier:bank";

    private SupplierPerms() {
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
