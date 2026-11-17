package com.lingmao.scm.module.purchase.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 算料结果行（数据库设计 §11.3）：一个物料 SKU 一行，公式见 PRD §6.6.1。数量除注明外是库存单位。
 * 已转采购、已领由动作改（NEVER）：已转采购 = 指着它的没作废的采购行数量合计（PurchaseOrderItemMapper.syncConverted）
 */
@Data
@TableName("mrp_calc_item")
public class MrpCalcItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long calcId;

    private Integer seq;

    private MrpBlock block;

    private Long materialSkuId;

    private Long supplierId;

    private BigDecimal grossQty;

    private BigDecimal reservePct;

    private BigDecimal needQty;

    private BigDecimal availStockQty;

    private BigDecimal useStockQty;

    private BigDecimal availTransitQty;

    private BigDecimal useTransitQty;

    private BigDecimal lateTransitQty;

    private BigDecimal netQty;

    private String purchaseUnit;

    private BigDecimal conversionFactor;

    /** 采购单位 */
    private BigDecimal suggestQty;

    /** 元 / 采购单位 */
    private BigDecimal refPrice;

    private BigDecimal estAmount;

    /** 采购单位 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal convertedQty;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal issuedQty;

    /** 逗号分隔，冗余显示 */
    private String orderNos;

    private String styleCodes;
}
