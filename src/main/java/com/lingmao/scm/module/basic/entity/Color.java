package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 颜色（bas_color，数据库设计 §5.5）：款式和物料共用一套，颜色码进 SKU 码 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_color")
public class Color extends AuditEntity {

    /** 2-3 位字母数字，大写，如 KQ；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    private String name;

    /** 色系，字典 color_family */
    private String family;

    /** 色块 #RRGGBB，只用于显示 */
    private String hex;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
