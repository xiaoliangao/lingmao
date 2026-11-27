package com.lingmao.scm.module.settle.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 待对账：某主体、某家、某个月还没进对账单的单据 */
@Data
public class PendingStatementRow {
    private Long companyId;
    private String companyName;
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    /** yyyy-MM */
    private String month;
    /** 这个月的 1 号和月底，生成对账单时直接用 */
    private LocalDate periodStart;
    private LocalDate periodEnd;
    /** 几张单据（入库单、退货单、回货单、扣款各算一张） */
    private Integer docCount;
    /** 实付（货款 − 扣款） */
    private BigDecimal amount;
}
