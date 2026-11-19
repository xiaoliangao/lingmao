package com.lingmao.scm.module.purchase.entity;

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
 * 采购单（数据库设计 §12.1，docs/api/03 §2.2）：草稿 → 待审核 →（多级审批）已审核 → 部分到货 → 已到齐；手动结案；待审核时作废。
 * 已审核、部分到货、已到齐由入库单、出库单过账时自动换（PurchaseStockHandler）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pur_order")
public class PurchaseOrder extends DocEntity {

    private Long supplierId;

    private PurchaseType purchaseType;

    /** 品类线（RFC 013 §2）：从算料单生成的跟算料单（改不了），手工建的选；供应商要做这条线 */
    private String productLine;

    /** 从哪张算料单生成：建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long calcId;

    private Long buyerId;

    private LocalDate orderDate;

    private LocalDate deliveryDate;

    /** 交货地点：物料仓或工厂仓 */
    private Long warehouseId;

    /** 字典 settle_method；草稿能空，提交时必填 */
    private String settleMethod;

    private BigDecimal taxPct;

    private Boolean taxIncluded;

    private Boolean freightIncluded;

    /** 各行采购单位数量直接相加 */
    private BigDecimal totalQty;

    private BigDecimal totalAmount;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
