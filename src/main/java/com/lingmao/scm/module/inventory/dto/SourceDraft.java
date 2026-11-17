package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.biz.dto.DocRef;

import java.math.BigDecimal;
import java.util.List;

/**
 * 源单那边（StockSourceHandler.prefill）带出来的单头默认值和明细，只有 id 和数，名称由 inventory 补（InvDocDraftVO）。
 * 由源单建单时也按它取单价、换算系数、上限，前端传的这些不信
 *
 * @param companyId 主体：取源单的
 * @param source    源单的 DocRef
 * @param partnerId 往来：采购单的供应商、生产单的加工厂
 * @param fromWarehouseId 默认出仓（采购退货：交货地点；领料：第一个启用的物料仓；退料：加工厂的工厂仓），没有是 null
 * @param toWarehouseId   默认入仓
 */
public record SourceDraft(long companyId, DocRef source, Long partnerId, Long fromWarehouseId, Long toWarehouseId,
                          List<Item> items) {

    /**
     * 一行。数量单位：采购的两种是采购单位，领料、退料是库存单位
     *
     * @param sourceItemId 采购明细 id；领料、退料是 null（按 SKU 对行）
     * @param price        采购单价（元 / 采购单位），采购的两种才有
     * @param suggestQty   "本次"的默认值
     * @param maxQty       本次最多；领料不限是 null
     */
    public record Item(Long sourceItemId, long materialSkuId, String purchaseUnit, BigDecimal conversionFactor,
                       BigDecimal price, BigDecimal suggestQty, BigDecimal maxQty,
                       BigDecimal orderedQty, BigDecimal receivedQty, BigDecimal returnedQty,
                       BigDecimal needQty, BigDecimal issuedQty) {
    }
}
