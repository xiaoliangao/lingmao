package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 物料 SKU（bas_material_sku，数据库设计 §5.10）：物料 × 颜色 × 规格。
 * SKU 码、颜色、规格建好不改（SKU 码由它们拼出来）；能改的是色号、货号、单价、最小库存、备注
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_material_sku")
public class MaterialSku extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long materialId;

    /** 物料编码-颜色码[-规格码] */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    /** 无色物料为空 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long colorId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String spec;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String specCode;

    private String supplierColorNo;

    /** SKU 级货号；空 = 用物料的 */
    private String supplierItemNo;

    /** 单价，按采购单位、含税；空 = 用物料参考单价 */
    private BigDecimal price;

    /** 最小库存，库存单位 */
    private BigDecimal safetyStock;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;

    private String remark;
}
