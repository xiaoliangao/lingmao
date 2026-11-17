package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增、修改一条审批规则（前端 types/biz.ts 的 ApprovalRuleForm）。保存是整体覆盖：审批人从角色换成人，roleId 传 null。
 * 角色、人二选一，存在、启用，由 ApprovalRuleServiceImpl 查
 */
@Data
public class ApprovalRuleSaveReq {

    /** 只能是 ORDER、PURCHASE_ORDER、PRODUCTION_ORDER（多级审批只有这三种单据，架构 §4.8） */
    @NotNull(message = "选一种单据")
    private BizType bizType;

    @NotNull(message = "填一下金额超过多少")
    @DecimalMin(value = "0", message = "金额不能是负数")
    @Digits(integer = 12, fraction = 2, message = "金额最多 12 位整数、2 位小数")
    private BigDecimal overAmount;

    private Long roleId;
    private Long userId;
}
