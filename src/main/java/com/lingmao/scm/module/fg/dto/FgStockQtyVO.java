package com.lingmao.scm.module.fg.dto;

/** 一个 SKU 的现库存（前端 FgStockQty） */
public record FgStockQtyVO(Long companyId, Long warehouseId, Long styleSkuId, int qty) {
}
