package com.lingmao.scm.module.fg.dto;

/** 需求订单详情的"发 n"（前端 FgShippedRow）：已审核的发货出库，按 SKU 合计 */
public record FgShippedRow(Long styleSkuId, int shippedQty) {
}
