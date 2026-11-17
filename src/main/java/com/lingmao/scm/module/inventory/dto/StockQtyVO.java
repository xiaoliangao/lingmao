package com.lingmao.scm.module.inventory.dto;

import java.math.BigDecimal;

/** 一个仓库一个 SKU 的现库存（前端 StockQty）；没有库存记录是 0 */
public record StockQtyVO(Long materialSkuId, BigDecimal qty) {
}
