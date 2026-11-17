package com.lingmao.scm.module.production.dto;

import lombok.Data;

import java.time.LocalDate;

/** 挂在生产单上的领料出库单、退料入库单（前端 ProductionStockDoc）：草稿也列，作废的不列 */
@Data
public class ProductionStockDocVO {
    private Long id;
    private String no;
    private String kind;
    private String docType;
    private String status;
    private LocalDate docDate;
    private String fromWarehouseName;
    private String toWarehouseName;
    private int itemCount;
}
