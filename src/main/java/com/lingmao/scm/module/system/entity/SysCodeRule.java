package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import com.lingmao.scm.common.mybatis.StringArrayTypeHandler;
import com.lingmao.scm.module.system.dto.CodeSegment;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 编码规则（sys_code_rule，RFC 018 §4.8）。新表，没有逻辑删除 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sys_code_rule", autoResultMap = true)
public class SysCodeRule extends AuditEntity {

    /** CodeTarget 的名字；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String target;

    private String name;

    /** 按什么分的字典值；空 = 通用规则 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> scopeValues;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<CodeSegment> segments;

    private Integer sort;
}
