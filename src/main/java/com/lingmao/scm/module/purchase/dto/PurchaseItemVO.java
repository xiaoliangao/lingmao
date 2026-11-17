package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购明细的一行（前端 PurchaseItem）。数量、单价、已入库、已退货都按采购单位 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseItemVO extends MaterialSkuBrief {
    private Long id;
    private Integer seq;
    private String supplierItemNo;
    private String supplierColorNo;
    private String purchaseUnit;
    private BigDecimal conversionFactor;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private Long priceSourceId;
    /** 价格档案里这家在下单日的现价；没有是 null */
    private BigDecimal archivePrice;
    private String priceChangeReason;
    private LocalDate lineDueDate;
    /** 行交期 ?? 单头交货日期 */
    private LocalDate dueDate;
    private Long calcItemId;
    private Long calcId;
    private String calcNo;
    private List<String> orderNos;
    private List<String> styleCodes;
    private BigDecimal receivedQty;
    private BigDecimal returnedQty;
    private BigDecimal pendingQty;
    private LocalDate expectDate;
    private String followNote;
}
