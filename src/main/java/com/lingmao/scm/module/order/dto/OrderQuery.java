package com.lingmao.scm.module.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 需求订单列表（docs/api/02 §3.2）；counts 不看 status，导出多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQuery extends PageQuery {
    /** 单号、合同号、款号；一次查几个时单号、款号精确查 */
    private String keyword;
    private List<DocStatus> status;
    private String orderType;
    private String brand;
    private Long salesId;
    private Long merchId;
    /** 含这个款的 */
    private Long styleId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateTo;
    /** 交期（承诺交期 ?? 要求交期）到这天为止，含逾期的：首页近期交期用（docs/api/06 §2.4） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateTo;
    /** 按交期（承诺交期 ?? 要求交期）从早到晚排，不按下单日期：首页用 */
    private boolean dueFirst;
    /** 待排单：已审核、一件都还没排的 */
    private boolean unscheduled;
    private boolean mine;
    private boolean auditForMe;
    /** "待我审批"轮到我的单据 id：Service 查好了填进来，不用前端传的 */
    @JsonIgnore
    private List<Long> approvalIds = List.of();
    private List<Long> ids;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.lingmao.scm.module.biz.dto.ApprovalScope approvalScope;
}
