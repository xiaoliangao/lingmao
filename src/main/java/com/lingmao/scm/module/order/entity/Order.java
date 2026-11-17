package com.lingmao.scm.module.order.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求订单（数据库设计 §10.1，docs/api/02 §2.1）：草稿 → 待审核 →（多级审批）已审核 → 生产中 → 已完成；手动结案；审核前作废、审核后经变更单取消。
 * 已审核以后单头只有变更单能改（要求交期、合计），所以那几列不标 NEVER，由变更写回的条件更新改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_order")
public class Order extends DocEntity {

    /** 字典 order_type */
    private String orderType;

    /** 客户、客户订单号：TRADE 留口子，关着时为空 */
    private Long customerId;
    private String customerOrderNo;

    private String contractNo;

    /** 字典 brand */
    private String brand;

    private LocalDate orderDate;

    private LocalDate requiredDate;

    /** 承诺交期：审核时每一级都填（动作改） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate commitDate;

    private Long salesId;

    private Long merchId;

    /** 字典 currency */
    private String currency;

    private String deliveryReq;

    /** 合计：保存时由明细算好 */
    private Integer totalQty;
    private BigDecimal totalAmount;

    /** 来源：建单时定 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OrderSource source;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
