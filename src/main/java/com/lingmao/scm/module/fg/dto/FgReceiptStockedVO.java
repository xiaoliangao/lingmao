package com.lingmao.scm.module.fg.dto;

import java.util.List;

/** 数量按所有有效占用计算；单据预览/总数仅统计当前用户可见的成品单。 */
public record FgReceiptStockedVO(Long receiptId, List<Document> documents, long reservedQty, long postedQty,
                                 long remainingQty, long documentsTotal, boolean hasMoreDocuments) {
    public record Document(Long id, String no, String status, long qty) { }
}
