package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.order.entity.ChangeType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 变更明细的一处改动（前端 ChangeItemView） */
@Data
public class ChangeItemVO {
    private Long id;
    private Integer seq;
    private ChangeType changeType;
    private Long orderItemId;
    private Long styleId;
    private String styleCode;
    private Long styleSkuId;
    private String skuCode;
    private Integer fromQty;
    private Integer toQty;
    private LocalDate fromDate;
    private LocalDate toDate;
    private BigDecimal fromPrice;
    private BigDecimal toPrice;
    private Long fromTechPackId;
    private Integer fromVersion;
    private Long toTechPackId;
    private Integer toVersion;
    /** 这一处的说明，如"27CF001-KQ-M：120 → 100（已排 80）" */
    private String text;
}
