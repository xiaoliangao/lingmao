package com.lingmao.scm.module.price.dto;

/**
 * 批量取物料现价的一行（PriceService.currentMaterials）：哪家供应商、哪个物料、哪个 SKU。
 * 带供应商：资料包每行的默认供应商不一样（RFC 006 §4）。materialSkuId 为空 = 只要物料层的价
 */
public record MaterialPriceKey(long supplierId, long materialId, Long materialSkuId) {
}
