package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 需求订单这边的单据怎么出现在别处（变更提醒的来源、首页、1005 的清单）：订单不引用别人的单据，referrers 是空的；
 * 订单的负责人是跟单员，变更单的是制单人
 */
@Component
@RequiredArgsConstructor
class OrderReferrers implements ReferrerProvider {

    private final OrderMapper orderMapper;
    private final OrderChangeMapper changeMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return List.of();
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case ORDER -> orderMapper.selectByIds(ids).stream().collect(Collectors.toMap(Order::getId,
                    o -> DocRef.of(BizType.ORDER, o.getId(), o.getNo(), o.getStatus(), OrderServiceImpl.ORDER_STATUS, o.getMerchId())));
            case ORDER_CHANGE -> changeMapper.selectByIds(ids).stream().collect(Collectors.toMap(OrderChange::getId,
                    c -> DocRef.of(BizType.ORDER_CHANGE, c.getId(), c.getNo(), c.getStatus(), StatusLabels.DOC, c.getCreateBy())));
            default -> Map.of();
        };
    }
}
