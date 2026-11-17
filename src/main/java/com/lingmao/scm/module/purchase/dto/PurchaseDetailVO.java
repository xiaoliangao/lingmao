package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购单详情（前端 PurchaseDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseDetailVO extends DocHeaderVO {
    private Long supplierId;
    private String supplierCode;
    /** 全称 */
    private String supplierName;
    private String supplierShortName;
    private String supplierStatus;
    private String supplierContact;
    private String supplierPhone;
    private String purchaseType;
    private Long calcId;
    private String calcNo;
    private Long buyerId;
    private String buyerName;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    private Long warehouseId;
    private String warehouseName;
    private String warehouseType;
    private String warehouseAddress;
    private String settleMethod;
    private BigDecimal taxPct;
    private boolean taxIncluded;
    private boolean freightIncluded;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    private BigDecimal receivedQty;
    private BigDecimal returnedQty;
    private List<PlanStyleVO> planStyles;
    private List<PurchaseItemVO> items;
    private List<PurchaseStockDocVO> stockDocs;
}
