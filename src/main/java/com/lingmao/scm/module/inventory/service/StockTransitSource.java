package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.module.inventory.dto.WarehouseSku;

import java.util.List;

/**
 * 库存页要列的在途格子（采购那边实现，库存不依赖采购）：有没入齐的已审核采购行的 仓库 × 物料 SKU。
 * 库存是 0（或还没有库存记录）的格子只要有在途，库存明细、按物料默认也列出来（用户 11-22 定）；
 * 在途多少不在这里，页面另调 supplyApi.bySkus 取（docs/api/04 §3.6.1）
 */
public interface StockTransitSource {

    List<WarehouseSku> openTransitPlaces();
}
