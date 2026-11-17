package com.lingmao.scm.module.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 多级审批规则（biz_approval_rule，数据库设计 §4.4、架构 §4.8）：设置 › 审批里的一条——这种单据金额超过多少，再加一级，谁审。
 * 同一种单据几条就加几级，门槛低的先审。提交时抄进 biz_approval_step，之后改规则不影响已经提交的单
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_approval_rule")
public class ApprovalRule extends AuditEntity {

    /** ORDER / PURCHASE_ORDER / PRODUCTION_ORDER（外发加工单、成品采购单共用） */
    private BizType bizType;

    /** 单据总金额超过它（不含等于）才加这一级 */
    private BigDecimal overAmount;

    /** 审批人：角色、指定的人二选一（ApprovalRuleServiceImpl 校验，库里有检查约束兜底） */
    private Long roleId;
    private Long userId;
}
