package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.purchase.service.NeedDateSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** 给 03 的要料日期（docs/api/05 §3.9）：一张订单已下单、生产中的生产单里"裁剪"最早的计划开始；还没审核（没节点）的不在返回里 */
@Component
@RequiredArgsConstructor
class ProductionNeedDates implements NeedDateSource {

    private final ProductionOrderMapper prdMapper;

    @Override
    public Map<Long, LocalDate> cutStartOf(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return prdMapper.selectNeedDates(orderIds, ProductionRules.CUT_NODE).stream()
                .collect(Collectors.toMap(ProductionOrderMapper.NeedDateRow::orderId, ProductionOrderMapper.NeedDateRow::needDate));
    }
}
