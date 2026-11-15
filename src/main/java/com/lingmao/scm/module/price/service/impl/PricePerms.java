package com.lingmao.scm.module.price.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.price.entity.PriceType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 价格档案的权限码（RFC 006 §8，V16）。录和作废不分开，按类型分两个码：物料价采购能改，加工价生产跟单能改（PRD §3.1）。
 * 接口上的 @PreAuthorize 先挡住两个都没有的；Service 再按这条价的类型查具体的那个（RFC 006 §7），
 * 读的是 JwtAuthFilter 放进 SecurityContext 的权限，和 @PreAuthorize 看的是同一份
 */
final class PricePerms {

    static final String LIST = "price:price:list";
    static final String MATERIAL_EDIT = "price:material:edit";
    static final String PROCESS_EDIT = "price:process:edit";

    private PricePerms() {
    }

    static String editOf(PriceType type) {
        return type == PriceType.MATERIAL ? MATERIAL_EDIT : PROCESS_EDIT;
    }

    /** 没有这一类的改价权限 → 403。提示里写角色页上那个勾的名字（V16 的按钮名） */
    static void checkEdit(PriceType type) {
        if (!has(editOf(type))) {
            throw new BizException(403, "你没有改" + type.getLabel() + "的权限：找管理员在角色里勾上「录、作废" + type.getLabel() + "」");
        }
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
