package com.lingmao.scm.module.fg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 成品出入库明细：一张单一个 SKU 一行（唯一索引）。库存日志不指向它，反审核回草稿后能删能改 */
@Data
@TableName("fg_doc_item")
public class FgDocItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Integer seq;

    private Long styleSkuId;

    /** 件，> 0 */
    private Integer qty;

    /** 回货入库：回货明细 */
    private Long prdReceiptItemId;

    /** 发货出库：订单行 */
    private Long orderItemId;

    /** 发货出库：订单行单价；别的为空 */
    private BigDecimal price;

    private BigDecimal amount;

    private String remark;
}
