package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.board.dto.OrderReview;
import com.lingmao.scm.module.board.service.OrderReviewService;
import com.lingmao.scm.module.order.dto.OrderReviewInfo;
import com.lingmao.scm.module.order.dto.OrderReviewStyle;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.BoardFactoryRow;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.purchase.dto.OrderShortageVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 审核对话框的评审信息（docs/api/02 §3.8）：订单这一块问 OrderService（看不见这张单就 404），
 * 缺料用算料同一套公式粗算（MrpService），加工厂在手取生产看板按工厂的口径（ProductionBoardService）
 */
@Service
@RequiredArgsConstructor
public class OrderReviewServiceImpl implements OrderReviewService {

    /** 缺料给几种 */
    static final int SHORTAGE_SIZE = 5;

    private final OrderService orderService;
    private final MrpService mrpService;
    private final MaterialService materialService;
    private final ProductionBoardService productionBoardService;

    @Override
    public OrderReview review(long orderId) {
        OrderReviewInfo info = orderService.reviewInfo(orderId);
        List<OrderReview.Pack> packs = info.styles().stream().map(s -> new OrderReview.Pack(s.styleId(), s.styleCode(), s.styleName(),
                s.techPackId(), s.version(), s.problem())).toList();
        return new OrderReview(packs, shortages(info), loads(info.styles()));
    }

    /** 有生效的生产 BOM 的款按它展开；没有的款不算（资料包那一块已经说了不能审） */
    private List<OrderReview.Shortage> shortages(OrderReviewInfo info) {
        Map<Long, Long> stylePacks = new LinkedHashMap<>();
        info.styles().stream().filter(s -> s.techPackId() != null).forEach(s -> stylePacks.put(s.styleId(), s.techPackId()));
        List<OrderShortageVO> gaps = mrpService.orderShortages(info.orderId(), stylePacks, info.dueDate(), SHORTAGE_SIZE);
        if (gaps.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(gaps.stream().map(OrderShortageVO::materialSkuId)
                .collect(Collectors.toSet()));
        List<OrderReview.Shortage> out = new ArrayList<>();
        for (OrderShortageVO g : gaps) {
            MaterialSkuBrief b = briefs.get(g.materialSkuId());
            out.add(new OrderReview.Shortage(g.materialSkuId(), b == null ? "" : b.getSkuCode(), b == null ? "" : b.getMaterialName(),
                    b == null ? null : b.getColorName(), b == null ? null : b.getSpec(), b == null ? null : b.getStockUnit(),
                    g.need(), g.available(), g.gap(), g.leadDays(), b == null ? null : b.getImage()));
        }
        return out;
    }

    /** 每个默认加工厂：这张订单里哪些款给它，它手上在制多少（看板按工厂的那一行；看板上没有的厂不出） */
    private List<OrderReview.Load> loads(List<OrderReviewStyle> styles) {
        Map<Long, List<String>> codesByFactory = new LinkedHashMap<>();
        styles.stream().filter(s -> s.defaultFactoryId() != null)
                .forEach(s -> codesByFactory.computeIfAbsent(s.defaultFactoryId(), k -> new ArrayList<>()).add(s.styleCode()));
        if (codesByFactory.isEmpty()) {
            return List.of();
        }
        List<Long> ids = List.copyOf(codesByFactory.keySet());
        Map<Long, BoardFactoryRow> factories = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += 200) {
            BoardQuery query = new BoardQuery();
            query.setFactoryIds(ids.subList(from, Math.min(from + 200, ids.size())));
            productionBoardService.factories(query).forEach(factory -> factories.put(factory.getFactoryId(), factory));
        }
        return codesByFactory.entrySet().stream().map(e -> {
            BoardFactoryRow f = factories.get(e.getKey());
            return f == null ? null : new OrderReview.Load(e.getKey(), f.getFactoryShortName(), e.getValue(), f.getWipQty(), f.getWipCount());
        }).filter(Objects::nonNull).toList();
    }
}
