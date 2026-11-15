package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 尺码组（bas_size_group，数据库设计 §5.6）。建好以后只能在末尾追加尺码，基码不能换（RFC 003 §5.3）；名称能改。
 * 尺码在 bas_size_group_item
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_size_group")
public class SizeGroup extends AuditEntity {

    private String name;

    /** 基码，如 M；建好不能换 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String baseSize;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
