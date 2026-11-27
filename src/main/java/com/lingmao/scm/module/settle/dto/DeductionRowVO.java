package com.lingmao.scm.module.settle.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 扣款登记的一行 */
@Data
public class DeductionRowVO {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    private LocalDate bizDate;
    private BigDecimal amount;
    private String reason;
    private String refNo;
    /** 进了哪张没作废的对账单；还没对是空 */
    private Long statementId;
    private String statementNo;
    private StatementStatus statementStatus;
    private Integer version;
    private Long createBy;
    private String creatorName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
