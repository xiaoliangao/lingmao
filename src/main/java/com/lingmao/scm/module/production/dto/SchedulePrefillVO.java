package com.lingmao.scm.module.production.dto;

import java.time.LocalDate;
import java.util.List;

/** GET /api/production-orders/schedule-prefill（前端 SchedulePrefill）：默认交期 = 承诺交期 − 10 天，默认生产跟单 = 订单的跟单员 */
public record SchedulePrefillVO(long orderId, String orderNo, LocalDate commitDate, LocalDate requiredDate, LocalDate defaultDueDate,
                                long merchId, String merchName, List<ScheduleStyleVO> styles) {
}
