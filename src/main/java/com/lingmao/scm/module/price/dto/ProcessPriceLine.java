package com.lingmao.scm.module.price.dto;

import java.math.BigDecimal;

/**
 * 生产单审核时记的加工价（RFC 006 §5）：工厂 × 款式 × 加工方式，元 / 件。
 * 外发加工单记清加工、半包的加工费，成品采购单记包工包料的成品单价（docs/api/05 §3.9）
 */
public record ProcessPriceLine(long supplierId, long styleId, String processMode, BigDecimal price,
                               BigDecimal taxPct, boolean taxIncluded, String reason) {
}
