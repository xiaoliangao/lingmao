package com.lingmao.scm.module.fg.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 从回货单带出（前端 FgReceiptDraft）：单头默认值 + 矩阵 + 每行；还没入是 0 的行不带 */
@Data
public class FgReceiptDraftVO {
    private Long companyId;
    private Long receiptId;
    private String receiptNo;
    private String prdOrderNo;
    private String prdOrderKind;
    private String factoryName;
    private LocalDate receiptDate;
    private Long warehouseId;
    private String warehouseName;
    private FgStyleOptionVO style;
    private List<Item> items;

    /** maxQty = 还没入的良品，也是默认值 */
    public record Item(Long prdReceiptItemId, Long styleSkuId, int qty, int defectQty, int goodQty, int stockedQty, int maxQty) {
    }
}
