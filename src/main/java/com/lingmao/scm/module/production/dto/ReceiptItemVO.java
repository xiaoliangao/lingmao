package com.lingmao.scm.module.production.dto;

import lombok.Data;

/** 回货明细一行（前端 ReceiptItem）：良品 = 回货 − 次品 */
@Data
public class ReceiptItemVO {
    private Long id;
    private Long prdOrderItemId;
    private Long styleSkuId;
    private String skuCode;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String sizeCode;
    private int qty;
    private int defectQty;
    private int goodQty;
    private String remark;
}
