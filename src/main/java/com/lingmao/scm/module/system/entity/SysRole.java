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
}
