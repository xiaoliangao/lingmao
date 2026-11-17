package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/**
 * 配料方式（tp_bom_item.sku_mode，PRD §6.4.3）：用哪个物料 SKU 怎么定。配料都存在 tp_bom_item_sku：
 * 固定 1 条（款色、尺码都空）；随款色每个款色 1 条；随尺码每个尺码 1 条；随款色和尺码每个款色 × 尺码 1 条
 */
@Getter
public enum BomSkuMode {
    FIXED("固定", false, false),
    BY_COLOR("随款色", true, false),
    BY_SIZE("随尺码", false, true),
    BY_COLOR_SIZE("随款色和尺码", true, true);

    private final String label;
    /** 配料按款色分：每条配料有 colorId */
    private final boolean byColor;
    /** 配料按尺码分：每条配料有 sizeCode */
    private final boolean bySize;

    BomSkuMode(String label, boolean byColor, boolean bySize) {
        this.label = label;
        this.byColor = byColor;
        this.bySize = bySize;
    }
}
