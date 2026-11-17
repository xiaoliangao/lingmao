package com.lingmao.scm.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.inventory.entity.InvCount;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.module.inventory.mapper.InvCountMapper;
import com.lingmao.scm.module.inventory.mapper.InvDocMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存这边的单据怎么出现在别处（README §4.3"下游单据"页签、1005 的清单、库存日志点单号）：
 * 采购单、生产单的下游里有由它生成的入库单、出库单（作废的不算）；入库单、出库单、盘点单自己的 DocRef
 */
@Component
@RequiredArgsConstructor
class InventoryReferrers implements ReferrerProvider {

    private final InvDocMapper invDocMapper;
    private final InvCountMapper countMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        if (type != BizType.PURCHASE_ORDER && type != BizType.PRODUCTION_ORDER) {
            return List.of();
        }
        return invDocMapper.selectList(new LambdaQueryWrapper<InvDoc>()
                        .eq(InvDoc::getSourceType, type.name())
                        .eq(InvDoc::getSourceId, id)
                        .ne(InvDoc::getStatus, DocStatus.VOIDED)
                        .orderByAsc(InvDoc::getId))
                .stream().map(InventoryReferrers::ref).toList();
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case INV_DOC -> invDocMapper.selectByIds(ids).stream().collect(Collectors.toMap(InvDoc::getId, InventoryReferrers::ref));
            case INV_COUNT -> countMapper.selectByIds(ids).stream().collect(Collectors.toMap(InvCount::getId,
                    c -> DocRef.of(BizType.INV_COUNT, c.getId(), c.getNo(), c.getStatus(), StatusLabels.DOC, c.getCreateBy())));
            default -> Map.of();
        };
    }

    private static DocRef ref(InvDoc d) {
        return DocRef.of(BizType.INV_DOC, d.getId(), d.getNo(), d.getStatus(), StatusLabels.DOC, d.getCreateBy())
                .withKind(d.getKind().name());
    }
}
