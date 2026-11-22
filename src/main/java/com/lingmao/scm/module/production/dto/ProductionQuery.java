package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** 外发加工单、成品采购单列表（docs/api/05 §3.2.1）；看板列表也用它（kind 不传 = 两种都看） */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductionQuery extends PageQuery {
    /** OUTSOURCE / BUY：两个菜单的列表必传 */
    private String kind;
    /** 单号、订单号、款号、款名、加工厂；一次查几个时单号、订单号、款号精确查 */
    private String keyword;
    private List<DocStatus> status;
    /** 和 status=RUNNING 一起用：true 部分回货、false 没回过货 */
    private Boolean returned;
    /** LATE / NEAR / NODE_LATE / ISSUE / UPSTREAM；DELAYED = 已逾期或节点延期（首页"生产延期"一组） */
    private String risk;
    private Long factoryId;
    private Long merchId;
    /** 字典 brand（款式的品牌） */
    private String brand;
    /** 按"预计交期 ?? 交期"，两头都含 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateTo;
    private boolean mine;
    private boolean auditForMe;
    /** "待我审批"轮到我的单据 id：Service 查好了填进来，不用前端传的 */
    @JsonIgnore
    private List<Long> approvalIds = List.of();
    private List<Long> ids;
    /** 看板的数字卡：RUNNING / WAITING / NEAR / LATE / NODE_LATE / ISSUE（看板列表才有） */
    private String card;
    /** 按交期（预计交期 ?? 交期）从早到晚排，不按风险分数：首页近期交期用 */
    private boolean dueFirst;
    /** 只要这几张需求订单的：首页跟单的"晚于开裁" */
    @jakarta.validation.constraints.Size(max = 100, message = "一次最多筛选 100 个订单号")
    private Collection<String> orderNos;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
    private boolean delayedFirst;
    private boolean cutRiskFirst;
    /** 订单评审仅查询其默认工厂。 */
    private java.util.Collection<Long> factoryIds;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.lingmao.scm.module.biz.dto.ApprovalScope approvalScope;
}
