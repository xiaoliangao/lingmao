package com.lingmao.scm.module.production.dto;

/** 超排（1004）时 data 里的一条：上限 = ⌊订单数 × 1.03⌋ */
public record ScheduleOverRow(String sku, int orderQty, int scheduledQty, int thisQty, int maxQty, String message) {
}
