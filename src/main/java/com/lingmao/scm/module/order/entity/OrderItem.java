package com.lingmao.scm.module.order.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求订单明细（数据库设计 §10.2）：一张单一个款式 SKU 一行。件数 0 只会是变更单减掉的：行留着（变更明细指着它），页面上不显示
 */
@Data
@TableName("ord_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Integer seq;

    private Long styleId;

    private Long styleSkuId;

    private Integer qty;

    private BigDecimal price;

    /** 件数 × 单价，两位小数；没单价是 null */
    private BigDecimal amount;

    /** 行交期，null = 用单头的要求交期 */
    private LocalDate lineDueDate;

    /** 审核时锁定的生产 BOM（动作改） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long techPackId;

    /** 已排产、已回货：下游回写（动作改） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer scheduledQty;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer returnedQty;

    private String remark;
}
