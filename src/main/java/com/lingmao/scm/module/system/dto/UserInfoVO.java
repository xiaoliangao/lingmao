package com.lingmao.scm.module.system.dto;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户：前端登录后拿它决定显示什么。
 * roles 角色编码；perms 权限标识，前端据此显示 / 隐藏按钮；menus 侧边栏菜单树
 */
public record UserInfoVO(Long id,
                         String username,
                         String nickname,
                         List<String> roles,
                         Set<String> perms,
                         List<MenuVO> menus) {
}
