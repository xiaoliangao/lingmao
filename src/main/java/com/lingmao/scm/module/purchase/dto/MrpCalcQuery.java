package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 算料单列表（docs/api/03 §3.2.1） */
@Data
@EqualsAndHashCode(callSuper = true)
public class MrpCalcQuery extends TaggedPageQuery {
    /** 算料单号、订单号、款号；一次查几个时这三个都精确查 */
    private String keyword;
    private List<MrpStatus> status;
    private Long createBy;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createDateTo;
    private boolean mine;
    /** 只要这几张（首页"上游已变更"按数据范围筛提醒用） */
    private List<Long> ids;
}
