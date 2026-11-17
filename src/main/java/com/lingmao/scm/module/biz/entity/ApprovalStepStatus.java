package com.lingmao.scm.module.biz.entity;

/**
 * 审批流里一级的状态（biz_approval_step.status，架构 §4.8）。只由 ApprovalService 的比较并交换改：
 * 提交时第 1 级 CURRENT、其余 WAITING；审过 CURRENT → PASSED，下一级 WAITING → CURRENT；驳回、撤回、作废时没审的改成 CANCELLED
 */
public enum ApprovalStepStatus {
    /** 等前面的级审完 */
    WAITING,
    /** 该它审 */
    CURRENT,
    PASSED,
    /** 这一轮被驳回、撤回、作废了 */
    CANCELLED
}
