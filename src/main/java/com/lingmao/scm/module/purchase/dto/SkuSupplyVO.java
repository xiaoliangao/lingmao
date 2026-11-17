package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;
import java.util.List;

/** 一个物料 SKU 的在途、占用、是否低于最小库存（前端 SkuSupply，库存页用） */
public record SkuSupplyVO(long materialSkuId, List<Transit> transits, BigDecimal heldQty, BigDecimal safetyStock, boolean belowSafety) {

    /** 送某个仓的在途，库存单位 */
    public record Transit(long warehouseId, BigDecimal qty) {
    }
}
