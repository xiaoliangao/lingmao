package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;

import java.util.List;

/**
 * 画颜色 × 尺码矩阵要的款式（前端 FgStyleOption）：成品单据的明细、按款看库存都按它画。
 * 停用的颜色、SKU 也在（status = 0），成品单据不按停用拦（老款的期初、退回的货要能录）
 */
public record FgStyleOptionVO(Long styleId, String code, String name, AttachmentVO image, List<String> sizes,
                              List<Color> colors, List<Sku> skus) {

    public record Color(Long id, String code, String name, String hex) {
    }

    public record Sku(Long id, Long colorId, String sizeCode, String code, int status) {
    }

    public static FgStyleOptionVO of(StyleOrderInfo s) {
        return new FgStyleOptionVO(s.id(), s.code(), s.name(), s.image(), s.sizes(),
                s.colors().stream().map(c -> new Color(c.id(), c.code(), c.name(), c.hex())).toList(),
                s.skus().stream().map(k -> new Sku(k.id(), k.colorId(), k.sizeCode(), k.code(), k.enabled() ? 1 : 0)).toList());
    }
}
