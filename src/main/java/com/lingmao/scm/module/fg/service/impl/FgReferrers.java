package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成品单据怎么出现在别处（RFC 008 §6.2）：回货单的下游是由它生成的成品入库单，需求订单的下游是发它的成品出库单（作废的都不算）。
 * 有下游时回货单不能作废、订单不能取消（1005，清单就是这里给的）
 */
@Component
@RequiredArgsConstructor
class FgReferrers implements ReferrerProvider {

    private final FgDocMapper docMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return switch (type) {
            case PRODUCTION_RECEIPT -> docMapper.selectByReceipt(id).stream().map(FgReferrers::ref).toList();
            case ORDER -> docMapper.selectByOrder(id).stream().map(FgReferrers::ref).toList();
            default -> List.of();
        };
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (type != BizType.FG_DOC || ids.isEmpty()) {
            return Map.of();
        }
        return docMapper.selectByIds(ids).stream().collect(Collectors.toMap(FgDoc::getId, FgReferrers::ref));
    }

    private static DocRef ref(FgDoc d) {
        return DocRef.of(BizType.FG_DOC, d.getId(), d.getNo(), d.getStatus(), StatusLabels.DOC, d.getCreateBy())
                .withKind(d.getKind().name());
    }
}
