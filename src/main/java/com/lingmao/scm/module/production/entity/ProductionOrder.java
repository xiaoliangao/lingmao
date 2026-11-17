package com.lingmao.scm.module.production.entity;

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
 * 外发加工单、成品采购单（数据库设计 §14.1），统称生产单。种类、来源订单、款式、资料包版本排单时定，之后不改（NEVER）；
 * 预计交期由上报异常改、领料状态由领料过账改、件数合计排单时算好，也都是 NEVER
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prd_order")
public class ProductionOrder extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private ProductionKind kind;

    private Long factoryId;

    /** 字典 process_mode：只能在同一种单里换 */
    private String processMode;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long orderId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long styleId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long techPackId;

    private LocalDate orderDate;

    private LocalDate dueDate;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate expectDate;

    private BigDecimal unitPrice;

    private Long priceSourceId;

    private String priceChangeReason;

    private BigDecimal taxPct;

    private Boolean taxIncluded;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer totalQty;

    private BigDecimal totalAmount;

    private Long merchId;

    private String settleMethod;

    private String terms;

    /** null = 不用领料（资料包里没有我方采购的料） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private MaterialIssueStatus issueStatus;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
