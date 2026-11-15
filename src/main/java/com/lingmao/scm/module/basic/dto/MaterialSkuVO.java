package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.math.BigDecimal;

/** 物料详情里的一个 SKU：带颜色的编码、名称、色块和主图（一个 SKU 一张图） */
public record MaterialSkuVO(Long id, String code, Long colorId, String colorCode, String colorName, String colorHex,
                            String spec, String specCode, String supplierColorNo, String supplierItemNo,
                            BigDecimal price, BigDecimal safetyStock, Integer status, String remark, AttachmentVO image) {

    public static MaterialSkuVO of(MaterialSku sku, Color color, AttachmentVO image) {
        return new MaterialSkuVO(sku.getId(), sku.getCode(), sku.getColorId(),
                color == null ? null : color.getCode(), color == null ? null : color.getName(), color == null ? null : color.getHex(),
                sku.getSpec(), sku.getSpecCode(), sku.getSupplierColorNo(), sku.getSupplierItemNo(),
                sku.getPrice(), sku.getSafetyStock(), sku.getStatus(), sku.getRemark(), image);
    }
}
