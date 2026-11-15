package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.MaterialSku;

import java.math.BigDecimal;

/**
 * 给下游用的物料 SKU（RFC 003 §7.3）：物料的那些（material）+ SKU 码、颜色、规格、单价、SKU 状态。
 * price 是 SKU 单价，空的用物料参考单价（还是空就是 null）；能不能选进新单据要物料和 SKU 都启用
 */
public record MaterialSkuRef(Long id, String code, MaterialRef material, Long colorId, String colorCode, String colorName,
                             String spec, String specCode, BigDecimal price, Integer status) {

    public static MaterialSkuRef of(MaterialSku sku, MaterialRef material, Color color) {
        return new MaterialSkuRef(sku.getId(), sku.getCode(), material, sku.getColorId(),
                color == null ? null : color.getCode(), color == null ? null : color.getName(),
                sku.getSpec(), sku.getSpecCode(), sku.getPrice() != null ? sku.getPrice() : material.refPrice(), sku.getStatus());
    }

    /** 物料和 SKU 都启用：能选进新单据 */
    public boolean selectable() {
        return Integer.valueOf(1).equals(status) && Integer.valueOf(1).equals(material.status());
    }
}
