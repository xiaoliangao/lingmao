package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 待到料（docs/api/03 §3.4，前端 PendingArrivalQuery） */
@Data
@EqualsAndHashCode(callSuper = true)
public class PendingArrivalQuery extends PageQuery {
    /** LATE 已逾期 / NEAR 3 天内 / CUT 晚于开裁；不传 = 全部 */
    private String tab;
    private String keyword;
    private Long supplierId;
    private Long buyerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateTo;
}
