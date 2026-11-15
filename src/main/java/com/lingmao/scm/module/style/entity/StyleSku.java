package com.lingmao.scm.module.style.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 款式 SKU（sty_style_sku，数据库设计 §8.3）：款式 × 颜色 × 尺码。SKU 码 = 款号-颜色码-尺码，生成时拼好不能改；
 * 能改的是条码、吊牌价（空 = 用款式的），状态走启用、停用
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sty_style_sku")
public class StyleSku extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long styleId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long colorId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sizeCode;

    /** 有值时全表唯一 */
    private String barcode;

    private BigDecimal tagPrice;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;
}
