package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.module.quality.entity.QcKind;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 待检的一行：一张采购入库单或回货单 */
@Data
public class QcPendingRow {
    /** INCOMING 来料检验 / FINAL 尾期验货 */
    private QcKind kind;
    /** 采购入库单 id 或回货单 id，记质检时原样传回 sourceId */
    private Long sourceId;
    private String sourceNo;
    /** 入库日期、回货日期 */
    private LocalDate sourceDate;
    private Long companyId;
    private String companyName;
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    private Long prdOrderId;
    private String prdOrderKind;
    private String prdOrderNo;
    private String productLine;
    private String targetDesc;
    /** 批量：入库的采购数量合计（几种单位混在一起时空）、回货件数 */
    private BigDecimal lotQty;
    private String unit;
    /** 这次是第几次验：返修回来再验的大于 1 */
    private Integer attemptNo;
}
