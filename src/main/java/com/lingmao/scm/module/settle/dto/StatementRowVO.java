package com.lingmao.scm.module.settle.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 对账单列表的一行 */
@Data
public class StatementRowVO {
    private Long id;
    private String no;
    private Long companyId;
    private String companyName;
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate statementDate;
    private StatementStatus status;
    /** 货款合计（退货是负的） */
    private BigDecimal totalAmount;
    private BigDecimal deductAmount;
    /** 实付 = 货款 − 扣款 */
    private BigDecimal payableAmount;
    /** 对方对账单上的合计；没比对过是空 */
    private BigDecimal theirAmount;
    /** 比对出几处不一致；没比对过是空 */
    private Integer diffCount;
    /** 明细几行 */
    private Integer itemCount;
    private String remark;
    private Long createBy;
    private String creatorName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
