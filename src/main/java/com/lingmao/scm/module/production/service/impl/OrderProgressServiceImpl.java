package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.dto.OrderProgressRiskVO;
import com.lingmao.scm.module.production.dto.OrderProgressRowVO;
import com.lingmao.scm.module.production.dto.ProductionBriefVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.mapper.OrderProgressMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.service.OrderProgressService;
import com.lingmao.scm.module.purchase.dto.OrderKittingVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单进度总览（RFC 016 §1）。行和风险由 OrderProgressMapper 现算；这里补款式主图、跟单名字、齐料（purchase）、
 * 这个款的生产单小块（和外发加工单列表同一段 SQL，只按品类线筛：能看订单的人在"下游单据"里本来就看得到这些生产单）
 */
@Service
@RequiredArgsConstructor
public class OrderProgressServiceImpl implements OrderProgressService {

    /** 生产单小块不列作废的 */
    private static final List<DocStatus> SHOWN = Arrays.stream(DocStatus.values()).filter(s -> s != DocStatus.VOIDED).toList();

    private final OrderProgressMapper progressMapper;
    private final ProductionOrderMapper prdMapper;
    private final ProductionSupport support;
    private final MrpService mrpService;
    private final AttachmentService attachmentService;
    private final UserService userService;

    @Override
    public PageResult<OrderProgressRowVO> page(OrderProgressQuery query) {
        // 一次查几个的个数、长度在这里先查（超了 400）；放到 XML 的 bind 里抛，会被 MyBatis 包成 500
        Keywords.codes(query.getKeyword());
        DataScope scope = DataScope.current(query.isMine());
        IPage<OrderProgressRowVO> page = progressMapper.selectRowPage(query.toPage(), query, scope, support.today(), support.nearUntil());
        fill(page.getRecords(), scope);
        return PageResult.of(page);
    }

    @Override
    public OrderProgressCounts counts(OrderProgressQuery query) {
        Keywords.codes(query.getKeyword());
        return progressMapper.selectCounts(query, DataScope.current(query.isMine()), support.today(), support.nearUntil());
    }

    private void fill(List<OrderProgressRowVO> rows, DataScope scope) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> orderIds = rows.stream().map(OrderProgressRowVO::getOrderId).distinct().toList();
        Map<Long, OrderKittingVO> kitting = mrpService.kitting(orderIds).stream()
                .collect(Collectors.toMap(OrderKittingVO::orderId, Function.identity(), (a, b) -> a));
        ProductionQuery pq = new ProductionQuery();
        pq.setOrderNos(rows.stream().map(OrderProgressRowVO::getOrderNo).distinct().toList());
        pq.setStyleIds(rows.stream().map(OrderProgressRowVO::getStyleId).distinct().toList());
        pq.setStatus(SHOWN);
        List<ProductionRowVO> prds = prdMapper.selectRows(pq, null, scope.linesOnly(), support.today(), support.nearUntil());
        support.fillProgress(prds);
        Map<String, List<ProductionBriefVO>> byKey = prds.stream()
                .sorted(Comparator.comparing(ProductionRowVO::getId))
                .collect(Collectors.groupingBy(p -> key(p.getOrderId(), p.getStyleId()),
                        Collectors.mapping(OrderProgressServiceImpl::brief, Collectors.toList())));
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE,
                rows.stream().map(OrderProgressRowVO::getStyleId).collect(Collectors.toSet()), Attachment.IMAGE);
        Map<Long, String> names = userService.nicknames(rows.stream().map(OrderProgressRowVO::getMerchId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        for (OrderProgressRowVO r : rows) {
            r.setKitting(kitting.get(r.getOrderId()));
            r.setProductions(byKey.getOrDefault(key(r.getOrderId(), r.getStyleId()), List.of()));
            r.setImage(images.get(r.getStyleId()));
            r.setMerchName(names.get(r.getMerchId()));
            r.setRisk(new OrderProgressRiskVO(r.isRiskLate(), r.isRiskNear(), r.isRiskUnscheduled(),
                    r.isRiskNodeLate(), r.isRiskIssue(), r.isRiskProduction()));
        }
    }

    private static String key(Long orderId, Long styleId) {
        return orderId + ":" + styleId;
    }

    private static ProductionBriefVO brief(ProductionRowVO p) {
        return new ProductionBriefVO(p.getId(), p.getNo(), p.getKind(), p.getStatus(), p.getFactoryShortName(), p.getTotalQty(),
                p.getReturnedQty(), p.getDefectQty(), p.getDueDate(), p.getExpectDate(), p.getDaysLeft(), p.getCurrentNodeName(),
                p.getNodes(), p.getRisk());
    }
}
