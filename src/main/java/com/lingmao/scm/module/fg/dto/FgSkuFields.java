package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import lombok.Data;

/** 一个款式 SKU 的显示字段（前端 StyleSkuBrief）：单据明细、库存、日志的行都继承它，查询时现取 */
@Data
public abstract class FgSkuFields {

    private Long styleSkuId;
    /** 款号-颜色码-尺码 */
    private String skuCode;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private Long colorId;
    private String colorName;
    private String colorHex;
    private String sizeCode;
    private AttachmentVO image;

    /** 从款式信息里取这个 SKU；款式被删了（style 是 null）只留 id */
    public void fillSku(long skuId, StyleOrderInfo style) {
        styleSkuId = skuId;
        if (style == null) {
            return;
        }
        styleId = style.id();
        styleCode = style.code();
        styleName = style.name();
        image = style.image();
        StyleOrderInfo.Sku sku = style.skus().stream().filter(k -> k.id() == skuId).findFirst().orElse(null);
        if (sku == null) {
            return;
        }
        skuCode = sku.code();
        sizeCode = sku.sizeCode();
        colorId = sku.colorId();
        style.colors().stream().filter(c -> c.id() == sku.colorId()).findFirst().ifPresent(c -> {
            colorName = c.name();
            colorHex = c.hex();
        });
    }
}
