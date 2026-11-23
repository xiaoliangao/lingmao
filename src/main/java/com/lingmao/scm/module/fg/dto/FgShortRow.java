package com.lingmao.scm.module.fg.dto;

/** 1001 库存不够的一行（docs/api/07 §3.5.1）：同仓同 SKU 的几行合成一行 */
public record FgShortRow(String seqs, String warehouseName, String skuCode, String styleName, String colorName, String sizeCode,
                         int stockQty, int needQty, int shortQty) {
}
