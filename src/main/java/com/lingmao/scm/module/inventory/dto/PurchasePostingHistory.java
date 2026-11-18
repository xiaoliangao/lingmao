package com.lingmao.scm.module.inventory.dto;

import java.math.BigDecimal;

/** 尚未冲销的采购库存原事件；已冲销的历史行不会从当前可编辑草稿推导旧金额。 */
public record PurchasePostingHistory(long sourceItemId, long materialSkuId, String changeType,
                                     BigDecimal purchaseQty, BigDecimal conversionFactor, BigDecimal purchasePrice,
                                     BigDecimal quantity, BigDecimal amount, Integer roundingVersion) { }
