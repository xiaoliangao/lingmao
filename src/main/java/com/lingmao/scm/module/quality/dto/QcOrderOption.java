package com.lingmao.scm.module.quality.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 记产前样、中检时选的生产单 */
@Data
public class QcOrderOption {
    private Long id;
    private String no;
    /** OUTSOURCE 外发加工单 / BUY 成品采购单 */
    private String kind;
    private String status;
    private Long companyId;
    private String styleCode;
    private String styleName;
    private Long partnerId;
    private String partnerName;
    private BigDecimal totalQty;
}
