package com.lingmao.scm.security;

import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 只给超级管理员的接口这样写：@PreAuthorize("@superAdmin.check(authentication)")。
 * 超级管理员 = 有启用的 admin 角色，和菜单权限里的判断一样（SysMenuMapper）。
 * 为什么不用 hasAuthority：这类接口（如备份状态）不挂在任何菜单下，没有权限标识；为了它造一个菜单再分给 admin，是绕了一圈
 */
@Component("superAdmin")
@RequiredArgsConstructor
public class SuperAdminCheck {

    private final PermissionService permissionService;

    /** 没登录、或者登录的不是我们的用户，一律不是 */
    public boolean check(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser user)) {
            return false;
        }
        return permissionService.loadRoleCodes(user.userId()).contains(SysRole.ADMIN_CODE);
    }
}
