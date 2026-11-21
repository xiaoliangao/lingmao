package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 待到料的一行（前端 PendingArrivalRow）：已审核、部分到货的采购单里没入齐的行。数量按采购单位 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PendingArrivalRowVO extends MaterialSkuBrief {
    private Long purchaseItemId;
    private Long purchaseOrderId;
    private String purchaseNo;
    private String purchaseType;
    private Long supplierId;
    private String supplierName;
    private Long buyerId;
    private String buyerName;
    private Long warehouseId;
    private String warehouseName;
    private String purchaseUnit;
    private BigDecimal qty;
    private BigDecimal receivedQty;
    private BigDecimal returnedQty;
    /** 冲入：同组别的单多收、冲给这一行的（RFC 014） */
    private BigDecimal offsetInQty;
    /** 冲出：这一行多收、冲给同组别的单的 */
    private BigDecimal offsetOutQty;
    private BigDecimal pendingQty;
    private LocalDate dueDate;
    private LocalDate expectDate;
    private String followNote;
    private LocalDate arriveDate;
    private long daysLeft;
    private String risk;
    private LocalDate needDate;
    private long cutLateDays;
    private List<String> orderNos;
    private List<String> styleCodes;
    private String calcNo;
    private List<String> actions;

    /** 算要料日期、填行用，不返回 */
    @JsonIgnore
    private Long calcItemId;
    @JsonIgnore
    private Long createBy;
}
