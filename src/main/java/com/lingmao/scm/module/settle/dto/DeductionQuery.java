package com.lingmao.scm.module.settle.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 扣款登记列表 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeductionQuery extends PageQuery {
    private Long companyId;
    private Long partnerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
    /** true = 已进了没作废的对账单，false = 还没对；不传是全部 */
    private Boolean settled;
    /** 原因、相关单号、供应商的编码、名称、简称（包含） */
    private String keyword;
}
