package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 款式品类（bas_category，数据库设计 §5.4）：三级树，如 女装 > 外套 > 风衣 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_category")
public class Category extends AuditEntity {

    /** 上级，顶层为空；建好不改（不挪节点） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long parentId;

    private String name;

    /** 1-3，由上级算 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer level;

    private Integer sort;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
