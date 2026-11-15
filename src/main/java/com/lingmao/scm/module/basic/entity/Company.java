package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 主体公司（bas_company，数据库设计 §5.1）：甲方名下做生意的主体，每张单据记 company_id */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_company")
public class Company extends AuditEntity {

    /** 2-6 位字母数字，大写；建好不改（RFC 003 §2） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    private String name;
    private String shortName;
    private String creditCode;
    private String address;
    private String phone;

    /** 1 启用 0 停用，只由启用、停用动作改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
