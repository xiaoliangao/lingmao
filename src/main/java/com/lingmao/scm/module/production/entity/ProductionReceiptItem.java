package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 回货明细（数据库设计 §14.7）：登记以后不改 */
@Data
@TableName("prd_receipt_item")
public class ProductionReceiptItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiptId;

    private Integer seq;

    private Long prdOrderItemId;

    private Long styleSkuId;

    /** 本次回货，含次品 */
    private Integer qty;

    private Integer defectQty;

    private String remark;
}
