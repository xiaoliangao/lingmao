package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    /** 超级管理员的角色编码：拥有全部权限，不能修改、删除、分配菜单 */
    public static final String ADMIN_CODE = "admin";

    private String code;
    private String name;
    private Integer sort;
    private Integer status;
    private String remark;
    /** 数据范围：全部 / 本人（V8）。超级管理员是全部 */
    private RoleDataScope dataScope;
    /** 审核不受数据范围限制（V49）：本人范围的角色勾上后，单级审核的单据待审时不按本人筛。数据范围是全部的存 false */
    private Boolean auditAnyScope;
}
