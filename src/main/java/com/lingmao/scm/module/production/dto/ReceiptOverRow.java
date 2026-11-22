package com.lingmao.scm.module.production.dto;

/**
 * 超回货（1004）时 data 里的一条。良品 = 回货 − 次品（甲方 11-19：只算良品）
 *
 * @param qty         加工件数
 * @param goodQty     已回良品
 * @param thisGoodQty 这次的良品
 * @param maxQty      良品上限 = ⌊件数或裁床数 × 1.05⌋
 */
public record ReceiptOverRow(String sku, int qty, int goodQty, int thisGoodQty, int maxQty, String message) {
}
