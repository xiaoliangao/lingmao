package com.lingmao.scm.module.price.dto;

import com.lingmao.scm.module.price.entity.Price;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 取到的现价（RFC 006 §4），给资料包、采购单、排单带默认单价用，单据明细的 price_source_id 存 id。
 * price 的单位是 unit：物料价是物料现在的采购单位（只认这个单位的记录），加工价是 PIECE。
 * 含税、含运的口径原样给，和单据的口径不一样时怎么办由单据的 RFC 定。
 * materialSkuId 为空 = 物料层的价（这个物料所有颜色同价）；加工价也是空
 */
public record PriceRef(long id, BigDecimal price, String unit, BigDecimal taxPct,
                       boolean taxIncluded, boolean freightIncluded, LocalDate effectiveDate,
                       Long materialSkuId) {

    public static PriceRef of(Price p) {
        return new PriceRef(p.getId(), p.getPrice(), p.getUnit(), p.getTaxPct(),
                Boolean.TRUE.equals(p.getTaxIncluded()), Boolean.TRUE.equals(p.getFreightIncluded()), p.getEffectiveDate(),
                p.getMaterialSkuId());
    }
}
