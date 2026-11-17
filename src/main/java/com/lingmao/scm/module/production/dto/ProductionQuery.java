package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
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
    /** LATE / NEAR / NODE_LATE / ISSUE / UPSTREAM */
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
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<Long> approvalIds = List.of();
    private List<Long> ids;
    /** 看板的数字卡：RUNNING / WAITING / NEAR / LATE / NODE_LATE / ISSUE（看板列表才有） */
    private String card;
}
