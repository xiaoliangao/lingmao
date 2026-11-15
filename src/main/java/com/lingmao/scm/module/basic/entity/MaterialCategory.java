package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物料分类（bas_material_category，数据库设计 §5.8）：两级。一级只有名称（面料、辅料……）；
 * 二级必须有 2 位大写字母编码，就是物料编码的前缀。物料只能挂在启用的二级上
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_material_category")
public class MaterialCategory extends AuditEntity {

    /** 上级，一级为空；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long parentId;

    /** 二级的编码，如 ML；一级为空。建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    private String name;

    /** 1 或 2 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer level;

    private Integer sort;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
