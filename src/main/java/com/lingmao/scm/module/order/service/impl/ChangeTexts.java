package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.module.order.entity.OrderChangeItem;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.service.TechPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 变更单每一处改动的说明（ChangeItemVO.text）和整张的一句话（summary）：变更单列表、详情、订单详情顶上的黄条、给下游的变更提醒都用。
 * "27CF001-KQ-M：120 → 100""27CF001 单价：89 → 99""要求交期：03-20 → 03-25"。原单价按尺码不一样或没填时写"—"
 */
@Component
@RequiredArgsConstructor
class ChangeTexts {

    /** summary 最多列几处，再多写"等 n 处" */
    static final int SUMMARY_ITEMS = 6;

    static final String CANCEL = "取消订单";

    private final StyleService styleService;
    private final TechPackService techPackService;

    /** 说明里要用的名字：款号、SKU 码、资料包版本 */
    record Names(Map<Long, String> styleCodes, Map<Long, String> skuCodes, Map<Long, PackBrief> packs) {
    }

    Names names(Collection<OrderChangeItem> items) {
        Map<Long, String> styleCodes = new HashMap<>();
        Map<Long, String> skuCodes = new HashMap<>();
        List<Long> styleIds = items.stream().map(OrderChangeItem::getStyleId).filter(Objects::nonNull).distinct().toList();
        for (StyleOrderInfo info : styleService.orderInfos(styleIds).values()) {
            styleCodes.put(info.id(), info.code());
            info.skus().forEach(k -> skuCodes.put(k.id(), k.code()));
        }
        Map<Long, PackBrief> packs = techPackService.briefs(items.stream()
                .flatMap(i -> Stream.of(i.getFromTechPackId(), i.getToTechPackId())).filter(Objects::nonNull).toList());
        return new Names(styleCodes, skuCodes, packs);
    }

    /** 一处改动的说明；scheduled 是这一行现在的已排产（详情里数量的改动带"（已排 80）"），不需要传 null */
    static String text(OrderChangeItem i, Names n, Integer scheduled) {
        String sku = n.skuCodes().getOrDefault(i.getStyleSkuId(), "");
        String style = n.styleCodes().getOrDefault(i.getStyleId(), "");
        return switch (i.getChangeType()) {
            case QTY -> sku + "：" + i.getFromQty() + " → " + i.getToQty()
                    + (scheduled != null && scheduled > 0 ? "（已排 " + scheduled + "）" : "");
            case COLOR_SIZE -> i.getFromQty() == 0 ? sku + "：新加 " + i.getToQty() + " 件"
                    : sku + "：" + i.getFromQty() + " → 0，不要了";
            case PRICE -> style + " 单价：" + (i.getFromPrice() == null ? "—" : OrderTexts.plain(i.getFromPrice()))
                    + " → " + OrderTexts.plain(i.getToPrice());
            case TECH_PACK -> style + " 资料包：" + version(n, i.getFromTechPackId()) + " → " + version(n, i.getToTechPackId());
            case DUE -> "要求交期：" + OrderTexts.date(i.getFromDate(), i.getToDate()) + " → " + OrderTexts.date(i.getToDate(), i.getFromDate());
            case CANCEL -> CANCEL;
        };
    }

    private static String version(Names n, Long packId) {
        PackBrief p = packId == null ? null : n.packs().get(packId);
        return p == null ? "—" : p.versionName();
    }

    /** 整张的一句话：取消订单就是"取消订单"，不然前几处说明用"；"连起来 */
    static String summary(boolean cancel, List<String> texts) {
        if (cancel) {
            return CANCEL;
        }
        if (texts.size() <= SUMMARY_ITEMS) {
            return String.join("；", texts);
        }
        return String.join("；", texts.subList(0, SUMMARY_ITEMS)) + " 等 " + texts.size() + " 处";
    }
}
