package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;

import java.math.BigDecimal;

/**
 * 设置 › 审批的一条规则（前端 types/biz.ts 的 ApprovalRule）。审批人是角色或人，另一边是 null；名字翻好了
 * （删除了的角色、人也显示原来的名字，好知道这条要改）
 */
public record ApprovalRuleVO(Long id,
                             BizType bizType,
                             BigDecimal overAmount,
                             Long roleId,
                             String roleName,
                             Long userId,
                             String userName) {
}
