package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.order.entity.ChangeType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 变更单列表（docs/api/02 §3.14）；订单详情的"变更"页签带 orderId。counts 不看 status、rejected */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderChangeQuery extends PageQuery {
    /** 变更单号、订单号、SKU 码；一次查几个时变更单号、订单号精确查 */
    private String keyword;
    private List<DocStatus> status;
    /** 和 status=DRAFT 一起用：true 只要被驳回的，false 只要没被驳回的；null 都要 */
    private Boolean rejected;
    private Long orderId;
    private ChangeType changeType;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createDateTo;
    private boolean mine;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
}
