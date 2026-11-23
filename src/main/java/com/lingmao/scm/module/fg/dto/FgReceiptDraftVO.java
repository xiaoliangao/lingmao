package com.lingmao.scm.module.fg.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 从回货单带出（前端 FgReceiptDraft）：单头默认值 + 实际来源 SKU + 全部来源行（含剩余 0）；编辑可排除本草稿占用 */
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

    /** maxQty = remainingQty + 当前编辑草稿的本行数量（新建为 remainingQty）；三数量始终含全局有效占用 */
    public record Item(Long prdReceiptItemId, Long styleSkuId, int qty, int defectQty, int goodQty, int stockedQty, int maxQty,
                       int reservedQty, int postedQty, int remainingQty) {
    }
}
