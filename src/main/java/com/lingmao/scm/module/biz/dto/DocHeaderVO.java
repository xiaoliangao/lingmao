package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 单据详情的公共部分（docs/api/README §4.3，前端 types/biz.ts 的 DocHeader）：各单据的 XxxDetailVO 继承它，再加自己单头的业务字段和明细。
 * <pre>
 * XxxDetailVO vo = new XxxDetailVO();                         // 先填自己的字段
 * docHeaderService.fill(vo, BizType.PURCHASE_ORDER, po, po.getStatus());   // 单号、状态、版本、制单人、审核人、审批块、提醒数
 * vo.setCompanyName(…);                                        // 主体在 basic，biz 读不到，各域填（JOIN 或调 basic）
 * vo.setActions(DocActions.of(FLOW, po.getStatus())…approval(vo.getApproval()).list());   // 按钮最后算：要用审批块
 * </pre>
 * 为什么是继承的类不是 record：各单据在后面加字段，record 不能继承
 */
@Data
public abstract class DocHeaderVO {

    private Long id;
    private String no;

    /** 状态的英文名；中文页面按单据类型翻 */
    private String status;

    /** 保存草稿、状态动作都要带回来 */
    private Integer version;

    private Long companyId;
    private String companyName;
    private String remark;

    private Long createBy;
    private String createByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    private Long auditBy;
    private String auditByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime auditTime;

    /** 能点的按钮：状态允许 ∩ 有权限 ∩ 业务条件（架构 §4.2），用 DocActions 算 */
    private List<String> actions = List.of();

    /** 多级审批的单据（需求订单、采购单、生产单）才有，别的 null */
    private ApprovalView approval;

    /** 没处理的上游变更提醒条数，> 0 时详情顶上出黄条 */
    private int pendingNotices;
}
