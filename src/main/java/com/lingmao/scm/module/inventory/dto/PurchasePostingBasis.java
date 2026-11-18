package com.lingmao.scm.module.inventory.dto;

import java.math.BigDecimal;

/** 来源单锁内读取的冻结换算基准；netPurchaseQty 是本次来源回写完成后的净采购单位数。 */
public record PurchasePostingBasis(long materialSkuId, BigDecimal conversionFactor, BigDecimal purchasePrice,
                                   BigDecimal netPurchaseQty) { }
