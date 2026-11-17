package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 采购单列表的一行（前端 PurchaseRow） */
@Data
public class PurchaseRowVO {
    private Long id;
    private String no;
    private Long supplierId;
    private String supplierName;
    private String supplierStatus;
    private String purchaseType;
    private MaterialSkuBrief firstItem;
    private int itemCount;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    private Long warehouseId;
    private String warehouseName;
    /** 净入库合计（已入库 − 已退货），采购单位 */
    private BigDecimal receivedQty;
    private Long buyerId;
    private String buyerName;
    private String companyName;
    private String status;
    private Integer version;
    private List<String> actions;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    /** 第一行的 SKU、制单人：Service 填 firstItem、算按钮用，不返回 */
    @JsonIgnore
    private Long firstSkuId;
    @JsonIgnore
    private Long createBy;
}
