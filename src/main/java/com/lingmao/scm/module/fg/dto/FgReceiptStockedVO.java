package com.lingmao.scm.module.fg.dto;

/** 生产单回货页签的"已入库"（前端 FgReceiptStocked）：没作废的成品入库单 */
public record FgReceiptStockedVO(Long receiptId, Long docId, String no, String status) {
}
