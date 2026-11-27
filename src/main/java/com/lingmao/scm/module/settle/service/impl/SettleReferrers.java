package com.lingmao.scm.module.settle.service.impl;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.settle.entity.Statement;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import com.lingmao.scm.module.settle.mapper.StatementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对账单怎么出现在别处（RFC 019 §5.1）：进了没作废的对账单的采购入库、采购退货（入库单、出库单）不能反审核，回货单不能作废（1005，清单就是这里给的）
 */
@Component
@RequiredArgsConstructor
class SettleReferrers implements ReferrerProvider {

    static final StatusLabels<StatementStatus> STATUS = StatusLabels.of(StatementStatus.class)
            .with(StatementStatus.DRAFT, "草稿", StatusTone.OFF)
            .with(StatementStatus.SENT, "已发送", StatusTone.PENDING)
            .with(StatementStatus.CONFIRMED, "已确认", StatusTone.DONE)
            .with(StatementStatus.PAID, "已付款", StatusTone.DONE)
            .with(StatementStatus.VOIDED, "已作废", StatusTone.VOID);

    private final StatementMapper statementMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return switch (type) {
            case INV_DOC -> statementMapper.selectBySource(List.of("PURCHASE_IN", "PURCHASE_RETURN"), id).stream().map(SettleReferrers::ref).toList();
            case PRODUCTION_RECEIPT -> statementMapper.selectBySource(List.of("PRODUCTION_RECEIPT"), id).stream().map(SettleReferrers::ref).toList();
            default -> List.of();
        };
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (type != BizType.STATEMENT || ids.isEmpty()) {
            return Map.of();
        }
        return statementMapper.selectByIdList(ids).stream().collect(Collectors.toMap(Statement::getId, SettleReferrers::ref));
    }

    private static DocRef ref(Statement s) {
        return DocRef.of(BizType.STATEMENT, s.getId(), s.getNo(), s.getStatus(), STATUS, s.getCreateBy());
    }
}
