package com.lingmao.scm.module.production.dto;

import lombok.Data;

/** 生产单明细一行 = 一个款式 SKU（前端 ProductionItem） */
@Data
public class ProductionItemVO {
    private Long id;
    private Integer seq;
    private Long orderItemId;
    private Long styleSkuId;
    /** 款号-颜色码-尺码 */
    private String skuCode;
    private Long colorId;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String sizeCode;
    private int qty;
    private int returnedQty;
    private int defectQty;
    /** 未回 = 件数 − 已回货，不小于 0 */
    private int remainQty;
    /** 最多还能回 = ⌊件数 × 1.03⌋ − 已回货，不小于 0（Q09） */
    private int maxReceiveQty;
}
