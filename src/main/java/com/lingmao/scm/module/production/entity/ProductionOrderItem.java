package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 生产单明细（数据库设计 §14.2）：排单时定，件数不改；已回货、次品由回货单回写（NEVER） */
@Data
@TableName("prd_order_item")
public class ProductionOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long prdOrderId;

    private Integer seq;

    private Long orderItemId;

    private Long styleSkuId;

    private Integer qty;

    /** 已回货，含次品 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer returnedQty;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer defectQty;

    /** 裁床数（RFC 013 §4）：null = 没登记，回货上限、完成按件数；只由"登记裁床数"写（NEVER） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer cutQty;
}
