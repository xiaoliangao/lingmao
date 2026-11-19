package com.lingmao.scm.module.production.dto;

/** 超回货（1004）时 data 里的一条：加工件数、已回、这次、上限 = ⌊件数或裁床数 × 1.05⌋ */
public record ReceiptOverRow(String sku, int qty, int returnedQty, int thisQty, int maxQty, String message) {
}
