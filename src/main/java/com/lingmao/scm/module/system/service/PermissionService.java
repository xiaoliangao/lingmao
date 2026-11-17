package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.MenuVO;

import java.util.List;
import java.util.Set;

/** 某个用户"能做什么"：权限标识、角色、侧边栏菜单。过滤器每个请求都会调 loadPermissions */
public interface PermissionService {

    /**
     * 用户的全部权限标识：菜单和按钮的（只算自己和上级都启用的），数据范围是"全部"的再加一个 data:all。
     * 用户不存在、被删除或被停用时返回 null，表示"不认这个人了"
     */
    Set<String> loadPermissions(Long userId);

    List<String> loadRoleCodes(Long userId);

    /** 用户的启用角色 id（停用、删除的角色不算）：多级审批里"规则加的级"看审批角色在不在里面（架构 §4.8） */
    List<Long> loadRoleIds(Long userId);

    /** 侧边栏菜单树（只有目录和菜单） */
    List<MenuVO> loadMenuTree(Long userId);
}
