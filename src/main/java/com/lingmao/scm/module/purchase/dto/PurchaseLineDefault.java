package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;

/** 一个 SKU 在这家供应商的默认值（前端 PurchaseLineDefault）。单价按采购单位 */
public record PurchaseLineDefault(long materialSkuId, BigDecimal price, Long priceSourceId, BigDecimal archivePrice,
                                  String purchaseUnit, BigDecimal conversionFactor, Integer leadDays,
                                  String supplierItemNo, String supplierColorNo) {
}
