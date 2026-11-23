package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 成品单据、库存显示款式 SKU 用：款号款名、颜色、尺码、图都从款式那边现取（StyleService.boundedOrderInfos / boundedOrderInfosOfSkus，停用的颜色、SKU 也在）。
 * 名称改了旧单上跟着变，和物料的 skuBriefs 一样
 */
@Component
@RequiredArgsConstructor
class FgStyles {

    private final StyleService styleService;

    /** 款式 id → 款式信息，按传进来的顺序；查不到的（被删了）不在 map 里 */
    Map<Long, StyleOrderInfo> byId(Collection<Long> styleIds) {
        return styleIds.isEmpty() ? Map.of() : styleService.boundedOrderInfos(new LinkedHashSet<>(styleIds).stream().toList(), 50_000);
    }

    /** SKU id → 它所在款式的信息 */
    Map<Long, StyleOrderInfo> bySku(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        return styleService.boundedOrderInfosOfSkus(new LinkedHashSet<>(skuIds), 50_000);
    }

    static StyleOrderInfo.Sku sku(StyleOrderInfo style, long skuId) {
        return style == null ? null : style.skus().stream().filter(k -> k.id() == skuId).findFirst().orElse(null);
    }

    static String colorName(StyleOrderInfo style, StyleOrderInfo.Sku sku) {
        return style == null || sku == null ? null
                : style.colors().stream().filter(c -> c.id() == sku.colorId()).map(StyleOrderInfo.Color::name).findFirst().orElse(null);
    }

    /** SKU 码；查不到的用 id 顶上（提示里总得有个东西） */
    static String code(StyleOrderInfo style, long skuId) {
        StyleOrderInfo.Sku sku = sku(style, skuId);
        return sku == null ? "SKU " + skuId : sku.code();
    }
}
