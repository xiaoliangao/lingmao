package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCreatorProvider;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 生产单（外发加工单、成品采购单）的制单人，给多级审批用：自己建的单不能自己审（SelfAudit，甲方 11-21 答 Q10） */
@Component
@RequiredArgsConstructor
class ProductionCreators implements DocCreatorProvider {

    private final ProductionOrderMapper mapper;

    @Override
    public BizType type() {
        return BizType.PRODUCTION_ORDER;
    }

    @Override
    public Map<Long, Long> creators(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<ProductionOrder>().select(ProductionOrder::getId, ProductionOrder::getCreateBy).in(ProductionOrder::getId, ids))
                .stream().filter(d -> Objects.nonNull(d.getCreateBy()))
                .collect(Collectors.toMap(ProductionOrder::getId, ProductionOrder::getCreateBy));
    }
}
