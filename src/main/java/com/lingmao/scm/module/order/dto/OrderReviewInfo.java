package com.lingmao.scm.module.order.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 审核对话框里订单这一块（docs/api/02 §3.8）：board 再拿它去算缺料、看加工厂在手。
 *
 * @param dueDate 承诺交期 ?? 要求交期：待审核的单还没有承诺交期，粗算要料日期用要求交期
 */
public record OrderReviewInfo(long orderId, LocalDate dueDate, List<OrderReviewStyle> styles) {
}
