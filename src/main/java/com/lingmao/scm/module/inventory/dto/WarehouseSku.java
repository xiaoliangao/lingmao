package com.lingmao.scm.module.inventory.dto;

/** 一个库存格子：仓库 × 物料 SKU（StockTransitSource 给的在途格子） */
public record WarehouseSku(Long warehouseId, Long skuId) {
}
