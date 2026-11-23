package com.lingmao.scm.module.fg.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 明细一行（前端 FgDocItem） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocItemVO extends FgSkuFields {
    private Long id;
    private Integer seq;
    private Integer qty;
    private Long prdReceiptItemId;
    private Long orderItemId;
    private BigDecimal price;
    private BigDecimal amount;
    private String remark;
    /** 草稿出库单：出仓现库存 */
    private Integer stockQty;
    /** 草稿的回货入库、发货出库：这一行最多能填多少（含本单已填的） */
    private Long maxQty;
}
