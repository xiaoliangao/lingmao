package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.MenuVO;

import java.util.List;
import java.util.Set;

/** 某个用户"能做什么"：权限标识、角色、侧边栏菜单。过滤器每个请求都会调 loadPermissions */
public interface PermissionService {

    /** 用户的全部权限标识。用户不存在、被删除或被停用时返回 null，表示"不认这个人了" */
    Set<String> loadPermissions(Long userId);

    List<String> loadRoleCodes(Long userId);

    /** 侧边栏菜单树（只有目录和菜单） */
    List<MenuVO> loadMenuTree(Long userId);
}
