package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 算料单详情（前端 MrpCalcDetail）。auditBy、auditTime 是确认人、确认时间；approval 一直是 null */
@Data
@EqualsAndHashCode(callSuper = true)
public class MrpCalcDetailVO extends DocHeaderVO {
    private List<MrpOrderRef> orders;
    private List<String> styleCodes;
    private LocalDate needDate;
    private Long prevCalcId;
    private String prevCalcNo;
    private Long nextCalcId;
    private String nextCalcNo;
    private String closeReason;
    private String closeByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime closeTime;
    private BigDecimal estAmount;
    private BigDecimal convertedAmount;
    private List<MrpItemVO> items;
    /** 重新算料出来的单才有；不是的是 null */
    private List<MrpDiffRowVO> diff;
}
