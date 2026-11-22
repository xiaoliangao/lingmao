package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.inventory.dto.WarehouseSku;
import com.lingmao.scm.module.inventory.service.StockTransitSource;
import com.lingmao.scm.module.purchase.mapper.SupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 库存页的在途格子：已审核、部分到货的采购单里没入齐的行（和 SupplyMapper.selectLines 的"没入齐"同一个口径），按交货仓库 × SKU 去重 */
@Component
@RequiredArgsConstructor
class PurchaseTransitSource implements StockTransitSource {

    private final SupplyMapper supplyMapper;

    @Override
    public List<WarehouseSku> openTransitPlaces() {
        return supplyMapper.selectTransitPlaces().stream().map(p -> new WarehouseSku(p.warehouseId(), p.skuId())).toList();
    }
}
