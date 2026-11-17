package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.ApprovalRuleSaveReq;
import com.lingmao.scm.module.biz.dto.ApprovalRuleVO;

import java.util.List;

/**
 * 设置 › 审批（架构 §4.8）：金额超过多少再加一级、谁审。只给超级管理员。
 * 改规则只影响以后提交的单：已经提交的单的审批流是提交时抄的（biz_approval_step）
 */
public interface ApprovalRuleService {

    /** 全部规则，按单据类型、门槛从低到高排：同一种单据就是审的先后 */
    List<ApprovalRuleVO> list();

    /** 新增，返回 id。单据类型只能是三种之一，审批人角色、人二选一，要存在、启用 */
    Long create(ApprovalRuleSaveReq req);

    /** 整体覆盖 */
    void update(long id, ApprovalRuleSaveReq req);

    /** 直接删：提交过的单抄过规则，删了不影响它们 */
    void delete(long id);
}
