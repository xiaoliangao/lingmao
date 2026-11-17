package com.lingmao.scm.module.price.dto;

import java.math.BigDecimal;

/**
 * 单据审核时要记的一行物料价（RFC 006 §5）：采购单的一行。unit 是行上存的采购单位，和物料现在的采购单位不一样就不记。
 * reason 是行上的改价原因：有现价而它是空的，说明单据保存时漏了检查。加工价（生产单审核时记）等生产单开工时加
 */
public record PriceLine(long supplierId, long materialId, long materialSkuId, BigDecimal price, String unit,
                        BigDecimal taxPct, boolean taxIncluded, boolean freightIncluded, String reason) {
}
