package com.lingmao.scm.module.production.dto;

/**
 * 排单对话框里一个款的一个 SKU（前端 ScheduleSku）：remainQty = 订单数 − 已排产（不小于 0），默认填它；
 * maxQty = ⌊订单数 × 1.03⌋ − 已排产，这个 SKU 最多还能排多少（Q09）
 */
public record ScheduleSkuVO(long orderItemId, long styleSkuId, long colorId, String sizeCode, int orderQty, int scheduledQty,
                            int remainQty, int maxQty) {
}
