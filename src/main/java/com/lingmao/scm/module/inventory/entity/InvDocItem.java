package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 入库、出库明细（数据库设计 §13.2）。审核过的行被库存日志指着：反审核回草稿后能改数量、缸号，不能删、不能换物料 */
@Data
@TableName("inv_doc_item")
public class InvDocItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Integer seq;

    private Long materialSkuId;

    /** 物权，一期固定 OWN / 0 */
    private String ownerType;

    private Long ownerId;

    /** 库存单位，> 0 */
    private BigDecimal qty;

    private String purchaseUnit;

    private BigDecimal purchaseQty;

    private BigDecimal conversionFactor;

    /** 元 / 库存单位 */
    private BigDecimal price;

    private BigDecimal amount;

    private String dyeLot;

    private Integer rolls;

    private Long sourceItemId;

    private String overReason;

    private String remark;
}
