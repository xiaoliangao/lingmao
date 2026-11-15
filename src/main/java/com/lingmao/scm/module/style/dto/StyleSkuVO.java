package com.lingmao.scm.module.style.dto;

import java.math.BigDecimal;

/** 款式的一个 SKU。按颜色顺序、尺码顺序排。tagPrice 空 = 用款式的吊牌价 */
public record StyleSkuVO(Long id,
                         String code,
                         Long colorId,
                         String colorCode,
                         String colorName,
                         String colorHex,
                         String sizeCode,
                         String barcode,
                         BigDecimal tagPrice,
                         Integer status) {
}
