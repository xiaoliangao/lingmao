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
     * 自己和所有上级都启用、都没删除的菜单（bug 009）。权限和侧边栏都只从这里取：
     * 停用一个页面，它下面的按钮权限跟着失效；停用一个目录，整棵子树都失效。只看菜单自己的 status 的话，
     * 页面停用了按钮还在，接口照样调得通。
     * 从顶层往下走，碰到停用、删除的节点就不再往下，它的下级一个都走不到；上级不存在的孤儿也走不到。
     * 有环的数据（A 的上级是 B、B 的上级是 A）也到不了：环上没有顶层节点，从顶层出发进不去，递归不会停不下来
     */
    String ENABLED_MENUS = """
            WITH RECURSIVE enabled_menu AS (
                SELECT * FROM sys_menu WHERE parent_id = 0 AND deleted = false AND status = 1
                UNION ALL
                SELECT c.* FROM sys_menu c JOIN enabled_menu p ON c.parent_id = p.id
                WHERE c.deleted = false AND c.status = 1
            )
            """;

    /**
     * 用户拥有的全部权限标识。在上面那些菜单里，两种来源满足一个就算：
     * 1. 他有启用的 admin 角色（超级管理员）→ 所有菜单的权限
     * 2. 他的启用角色分配到了这个菜单，而且这个菜单不是管理后台的（admin_only = false）
     */
    @Select(ENABLED_MENUS + """
            SELECT DISTINCT m.perms FROM enabled_menu m
            WHERE m.perms IS NOT NULL AND m.perms <> ''
              AND (EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                           WHERE ur.user_id = #{userId} AND r.code = 'admin' AND r.status = 1 AND r.deleted = false)
                   OR (m.admin_only = false
                       AND m.id IN (SELECT rm.menu_id FROM sys_role_menu rm
                                    JOIN sys_user_role ur ON ur.role_id = rm.role_id
                                    JOIN sys_role r ON r.id = rm.role_id
                                    WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false)))
            """)
    List<String> selectPermsByUserId(@Param("userId") Long userId);

    @Select(ENABLED_MENUS + """
            SELECT EXISTS (
              SELECT 1 FROM sys_role r JOIN enabled_menu m ON m.perms = #{permission}
              WHERE r.id = #{roleId} AND r.status=1 AND r.deleted=false
                AND (r.code='admin' OR (m.admin_only=false AND EXISTS
                  (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id)))
                AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id
                            WHERE ur.role_id=r.id AND u.status=1 AND u.deleted=false)
            )
            """)
    boolean roleCanApprove(@Param("roleId") Long roleId, @Param("permission") String permission);

    /** 用户能在侧边栏看到的目录和菜单（不含按钮），规则同上 */
    @Select(ENABLED_MENUS + """
            SELECT m.* FROM enabled_menu m
            WHERE m.visible = true AND m.type IN (1, 2)
              AND (EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                           WHERE ur.user_id = #{userId} AND r.code = 'admin' AND r.status = 1 AND r.deleted = false)
                   OR (m.admin_only = false
                       AND m.id IN (SELECT rm.menu_id FROM sys_role_menu rm
                                    JOIN sys_user_role ur ON ur.role_id = rm.role_id
                                    JOIN sys_role r ON r.id = rm.role_id
                                    WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false)))
            ORDER BY m.sort, m.id
            """)
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId);
}
