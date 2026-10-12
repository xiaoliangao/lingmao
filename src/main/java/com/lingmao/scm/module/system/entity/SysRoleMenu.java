package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 角色-菜单关联，和 SysUserRole 一样只有两列 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role_menu")
public class SysRoleMenu {
    private Long roleId;
    private Long menuId;
}
