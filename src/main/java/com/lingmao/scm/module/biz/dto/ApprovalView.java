package com.lingmao.scm.module.biz.dto;

import java.util.List;

/**
 * 详情的"审批"块（架构 §4.8、前端 types/biz.ts 的 ApprovalView）：需求订单、采购单、生产单才有，最近一次提交的那一轮。
 *
 * @param round      第几次提交（驳回、撤回后重新提交加 1）
 * @param waitingFor 现在等谁审，如"第 2 级 · 总经理"；没在审（审完了、驳回了）是 null
 * @param canPass    当前用户能不能审这一级；详情 actions 里的 audit、reject 也按它出（DocActions.approval）
 */
public record ApprovalView(int round, List<ApprovalLevel> levels, String waitingFor, boolean canPass) {
}
