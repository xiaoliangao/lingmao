package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 部门（sys_dept，RFC 018 §1）：一棵树，最多 5 级；人在哪几个部门记在 sys_user_dept。新表，没有逻辑删除 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends AuditEntity {

    /** 上级，顶层为空。能换上级，但不能换到自己或自己的下级下面 */
    private Long parentId;

    private String name;

    /** 同级顺序，越小越靠前 */
    private Integer sort;
}
