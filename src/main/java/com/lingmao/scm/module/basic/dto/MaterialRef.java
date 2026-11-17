package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Material;

import java.math.BigDecimal;

/**
 * 给下游（价格档案、资料包、采购、出入库）用的物料（RFC 003 §7.3）：编码、名称、类型、单位和换算、参考单价、默认来源、损耗、备料比、
 * 默认供应商（资料包加 BOM 行时带出）、状态。
 * 能不能选进新单据由下游看 status（1 启用）
 */
public record MaterialRef(Long id, String code, String name, String materialType, String stockUnit, String purchaseUnit,
                          BigDecimal conversionFactor, BigDecimal refPrice, String defaultSource,
                          BigDecimal defaultLossPct, BigDecimal reservePct, Long defaultSupplierId, Integer status) {

    public static MaterialRef from(Material m) {
        return new MaterialRef(m.getId(), m.getCode(), m.getName(), m.getMaterialType(), m.getStockUnit(), m.getPurchaseUnit(),
                m.getConversionFactor(), m.getRefPrice(), m.getDefaultSource(), m.getDefaultLossPct(), m.getReservePct(),
                m.getDefaultSupplierId(), m.getStatus());
    }
}
