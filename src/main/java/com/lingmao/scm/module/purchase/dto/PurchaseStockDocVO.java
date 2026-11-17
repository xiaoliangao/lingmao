package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购单详情的"入库 / 退货"块（前端 PurchaseStockDoc）：这张单生成的物料入库单、出库单，草稿也列 */
@Data
public class PurchaseStockDocVO {
    private Long id;
    private String no;
    private String kind;
    private String docType;
    private String status;
    private LocalDate docDate;
    private String warehouseName;
    private BigDecimal purchaseQty;
    private List<String> dyeLots;
    private String logisticsNo;

    /** 缸号逗号连起来（SQL 的 string_agg），Service 拆成 dyeLots，不返回 */
    @JsonIgnore
    private String dyeLotsText;
}
