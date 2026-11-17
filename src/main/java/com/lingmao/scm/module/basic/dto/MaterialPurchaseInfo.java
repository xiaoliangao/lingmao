package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialSku;

import java.math.BigDecimal;

/**
 * 一个物料 SKU 买的时候要的东西（算料、采购单、补货提醒用，docs/api/03）：采购单位和换算、最小包装、起订量、采购周期、
 * 供应商货号色号（SKU 上的优先）、默认供应商、备料比、最小库存、SKU 单价（空的用物料参考单价）。
 * 能不能选进新单据看 selectable（物料和 SKU 都启用）
 *
 * @param price          元 / 采购单位：SKU 单价，没填用物料参考单价，都没有是 null
 * @param minPackQty     最小包装，采购单位；没设是 null
 * @param moq            起订量，采购单位；没设是 null
 * @param reservePct     物料上设的备料比 %，没设是 null（按物料类型）
 * @param safetyStock    最小库存，库存单位；没设是 null
 */
public record MaterialPurchaseInfo(long skuId, String skuCode, long materialId, String materialType, String purchaseUnit,
                                   BigDecimal conversionFactor, BigDecimal minPackQty, BigDecimal moq, Integer leadDays,
                                   String supplierItemNo, String supplierColorNo, Long defaultSupplierId, BigDecimal reservePct,
                                   BigDecimal safetyStock, BigDecimal price, boolean selectable) {

    public static MaterialPurchaseInfo of(MaterialSku k, Material m) {
        return new MaterialPurchaseInfo(k.getId(), k.getCode(), m.getId(), m.getMaterialType(), m.getPurchaseUnit(),
                m.getConversionFactor(), m.getMinPackQty(), m.getMoq(), m.getLeadDays(),
                k.getSupplierItemNo() != null ? k.getSupplierItemNo() : m.getSupplierItemNo(), k.getSupplierColorNo(),
                m.getDefaultSupplierId(), m.getReservePct(), k.getSafetyStock(), k.getPrice() != null ? k.getPrice() : m.getRefPrice(),
                Integer.valueOf(1).equals(k.getStatus()) && Integer.valueOf(1).equals(m.getStatus()));
    }
}
