package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.time.LocalDate;

/** 待入库的一张回货单（前端 FgPendingReceiptRow）。数量都是件 */
@Data
public class FgPendingReceiptRowVO {
    private Long receiptId;
    private String receiptNo;
    private Long prdOrderId;
    private String prdOrderNo;
    private String prdOrderKind;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private AttachmentVO image;
    private Long factoryId;
    private String factoryName;
    private Integer qty;
    private Integer defectQty;
    private Integer goodQty;
    private Integer stockedQty;
    private Integer remainQty;
    private Long warehouseId;
    private String warehouseName;
    private LocalDate receiptDate;
}
