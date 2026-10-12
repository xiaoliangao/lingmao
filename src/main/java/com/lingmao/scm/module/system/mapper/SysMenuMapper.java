package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 用户拥有的全部权限标识。两种来源满足一个就算：
     * 1. 他有启用的 admin 角色（超级管理员）→ 所有菜单的权限
     * 2. 他的启用角色分配到了这个菜单
     */
    @Select("""
            SELECT DISTINCT m.perms FROM sys_menu m
            WHERE m.deleted = false AND m.status = 1 AND m.perms IS NOT NULL AND m.perms <> ''
              AND (EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                           WHERE ur.user_id = #{userId} AND r.code = 'admin' AND r.status = 1 AND r.deleted = false)
                   OR m.id IN (SELECT rm.menu_id FROM sys_role_menu rm
                               JOIN sys_user_role ur ON ur.role_id = rm.role_id
                               JOIN sys_role r ON r.id = rm.role_id
                               WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false))
            """)
    List<String> selectPermsByUserId(@Param("userId") Long userId);

    /** 用户能在侧边栏看到的目录和菜单（不含按钮），规则同上 */
    @Select("""
            SELECT m.* FROM sys_menu m
            WHERE m.deleted = false AND m.status = 1 AND m.visible = true AND m.type IN (1, 2)
              AND (EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                           WHERE ur.user_id = #{userId} AND r.code = 'admin' AND r.status = 1 AND r.deleted = false)
                   OR m.id IN (SELECT rm.menu_id FROM sys_role_menu rm
                               JOIN sys_user_role ur ON ur.role_id = rm.role_id
                               JOIN sys_role r ON r.id = rm.role_id
                               WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false))
            ORDER BY m.sort, m.id
            """)
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId);
}
