package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户-角色关联。只有两个外键列，没有 id、审计字段和逻辑删除，所以不继承 BaseEntity */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_role")
public class SysUserRole {
    private Long userId;
    private Long roleId;
}
