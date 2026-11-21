package com.lingmao.scm.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCreatorProvider;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 需求订单的制单人，给多级审批用：自己建的单不能自己审（SelfAudit，甲方 11-21 答 Q10） */
@Component
@RequiredArgsConstructor
class OrderCreators implements DocCreatorProvider {

    private final OrderMapper mapper;

    @Override
    public BizType type() {
        return BizType.ORDER;
    }

    @Override
    public Map<Long, Long> creators(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<Order>().select(Order::getId, Order::getCreateBy).in(Order::getId, ids))
                .stream().filter(d -> Objects.nonNull(d.getCreateBy()))
                .collect(Collectors.toMap(Order::getId, Order::getCreateBy));
    }
}
