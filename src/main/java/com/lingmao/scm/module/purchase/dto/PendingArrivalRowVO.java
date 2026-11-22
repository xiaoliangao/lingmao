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
    /** 来源编码只展示排序后的前 20 个；总数为完整来源的去重数量。 */
    private long orderNosTotal;
    private List<String> styleCodes;
    private long styleCodesTotal;
    private String calcNo;
    /** 关联算料单总数；calcNo 仅展示优先的一个来源。 */
    private long sourcesTotal;
    private List<String> actions;

    /** 算要料日期、填行用，不返回 */
    @JsonIgnore
    private Long calcItemId;
    @JsonIgnore
    private Long createBy;
}
