package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.production.entity.IssueStatus;
import com.lingmao.scm.module.production.entity.ProductionIssue;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.module.production.mapper.ProductionIssueMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.module.biz.dto.StatusTone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生产这边的单据怎么出现在别处（README §4.3）：需求订单的下游有它排出来的生产单（作废的不算）；生产单的下游有回货单（作废的不算）
 * 和异常（领料退料单由库存那边给）；生产单、回货单、异常自己的 DocRef（回货单、异常挂在生产单详情里，parentId 是生产单 id）
 */
@Component
@RequiredArgsConstructor
class ProductionReferrers implements ReferrerProvider {

    /** 和前端 ISSUE_STATUS 一样 */
    static final StatusLabels<IssueStatus> ISSUE_STATUS = StatusLabels.of(IssueStatus.class)
            .with(IssueStatus.OPEN, "待处理", StatusTone.PENDING)
            .with(IssueStatus.HANDLING, "处理中", StatusTone.PROGRESS)
            .with(IssueStatus.CLOSED, "已关闭", StatusTone.CLOSED);

    private final ProductionOrderMapper prdMapper;
    private final ProductionReceiptMapper receiptMapper;
    private final ProductionIssueMapper issueMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return switch (type) {
            case ORDER -> prdMapper.selectLiveByOrder(id).stream().map(ProductionReferrers::ref).toList();
            case PRODUCTION_ORDER -> {
                List<DocRef> out = new ArrayList<>();
                receiptMapper.selectByOrder(id).stream().filter(r -> r.getStatus() != DocStatus.VOIDED)
                        .sorted(Comparator.comparing(ProductionReceipt::getId))
                        .forEach(r -> out.add(ref(r)));
                issueMapper.selectList(new LambdaQueryWrapper<ProductionIssue>().eq(ProductionIssue::getPrdOrderId, id)
                        .orderByAsc(ProductionIssue::getId)).forEach(i -> out.add(ref(i)));
                yield out;
            }
            default -> List.of();
        };
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case PRODUCTION_ORDER -> prdMapper.selectByIds(ids).stream().collect(Collectors.toMap(ProductionOrder::getId,
                    ProductionReferrers::ref));
            case PRODUCTION_RECEIPT -> receiptMapper.selectByIds(ids).stream().collect(Collectors.toMap(ProductionReceipt::getId,
                    ProductionReferrers::ref));
            case PRODUCTION_ISSUE -> issueMapper.selectByIds(ids).stream().collect(Collectors.toMap(ProductionIssue::getId,
                    ProductionReferrers::ref));
            default -> Map.of();
        };
    }

    private static DocRef ref(ProductionOrder p) {
        return DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), p.getStatus(), ProductionRules.STATUS, p.getMerchId())
                .withKind(p.getKind().name());
    }

    private static DocRef ref(ProductionReceipt r) {
        return DocRef.of(BizType.PRODUCTION_RECEIPT, r.getId(), r.getNo(), r.getStatus(), ProductionRules.RECEIPT_STATUS, r.getCreateBy())
                .withParent(r.getPrdOrderId());
    }

    private static DocRef ref(ProductionIssue i) {
        return DocRef.of(BizType.PRODUCTION_ISSUE, i.getId(), i.getNo(), i.getStatus(), ISSUE_STATUS, i.getCreateBy())
                .withParent(i.getPrdOrderId());
    }
}
