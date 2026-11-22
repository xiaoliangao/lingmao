package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 上游已变更（docs/api/06 §2.5）：首页 UPSTREAM 一组和 /upstream 页共用。
 * 提醒先在数据库按当前单头的负责人及数据范围授权、排序和分页，再有界补齐单据显示信息，
 * 审核权限不扩大范围
 */
@Component
@RequiredArgsConstructor
class UpstreamRows {

    private static final int CHUNK = 100;
    private final DocNoticeService noticeService;
    private final ReferenceService referenceService;
    private final ProductionOrderService productionService;
    private final PurchaseOrderService purchaseService;
    private final MrpService mrpService;

    record Row(DocRef doc, List<DocNoticeVO> notices, AttachmentVO image,
               OffsetDateTime latest, long noticesTotal) { }
    record Page(List<Row> rows, long total, boolean hasMore,
                com.lingmao.scm.module.biz.dto.PendingNoticePosition last) { }

    /** 授权、筛选、分组与分页都在 SQL 内完成；这里最多为 100 张单据补齐引用与缩略图。 */
    Page page(BizType only, Long owner, com.lingmao.scm.module.biz.dto.PendingNoticePosition after,
              boolean oldest, int limit) {
        Set<BizType> types = new LinkedHashSet<>();
        if (LoginUser.hasAuthority(HomeServiceImpl.PRODUCTION_LIST)) types.add(BizType.PRODUCTION_ORDER);
        if (LoginUser.hasAuthority(HomeServiceImpl.PURCHASE_LIST)) types.add(BizType.PURCHASE_ORDER);
        if (LoginUser.hasAuthority(HomeServiceImpl.MRP_LIST)) types.add(BizType.MRP_CALC);
        if (only != null) types.retainAll(Set.of(only));
        var page = noticeService.pendingPage(types, owner, after, oldest, limit);
        var groups = page.groups();
        Map<BizType, List<Long>> idsByType = new LinkedHashMap<>();
        groups.forEach(g -> idsByType.computeIfAbsent(g.docType(), k -> new ArrayList<>()).add(g.docId()));
        Map<BizType, Map<Long, AttachmentVO>> visible = new HashMap<>();
        Map<BizType, Map<Long, DocRef>> refs = new HashMap<>();
        idsByType.forEach((type, ids) -> {
            Map<Long, AttachmentVO> seen = visible(type, ids);
            visible.put(type, seen);
            refs.put(type, seen.isEmpty() ? Map.of() : referenceService.describe(type, seen.keySet()));
        });
        List<Row> rows = new ArrayList<>();
        for (var group : groups) {
            var seen = visible.get(group.docType());
            if (seen.containsKey(group.docId()) && !group.notices().isEmpty()) {
                DocRef ref = refs.get(group.docType()).get(group.docId());
                if (ref != null) rows.add(new Row(ref, group.notices(), seen.get(group.docId()), group.latest(), group.noticesTotal()));
            }
        }
        var last = groups.isEmpty() ? null : groups.getLast();
        return new Page(rows, page.total(), page.hasMore(), last == null ? null :
                new com.lingmao.scm.module.biz.dto.PendingNoticePosition(last.latest(), last.docType(), last.docId()));
    }

    /** 这几张里我在列表里看得见的（数据范围），和它们的缩略图 */
    private Map<Long, AttachmentVO> visible(BizType type, List<Long> ids) {
        Map<Long, AttachmentVO> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            switch (type) {
                case PRODUCTION_ORDER -> {
                    // 生产单列表要分种类查
                    for (String kind : HomeServiceImpl.PRODUCTION_KINDS) {
                        ProductionQuery q = sized(new ProductionQuery(), chunk.size(), p -> p.setIds(chunk));
                        q.setKind(kind);
                        for (ProductionRowVO r : productionService.page(q).getList()) {
                            out.put(r.getId(), r.getImage());
                        }
                    }
                }
                case PURCHASE_ORDER -> {
                    PurchaseQuery q = sized(new PurchaseQuery(), chunk.size(), p -> p.setIds(chunk));
                    for (PurchaseRowVO r : purchaseService.page(q).getList()) {
                        out.put(r.getId(), r.getFirstItem() == null ? null : r.getFirstItem().getImage());
                    }
                }
                case MRP_CALC -> {
                    MrpCalcQuery q = sized(new MrpCalcQuery(), chunk.size(), p -> p.setIds(chunk));
                    for (MrpCalcRowVO r : mrpService.page(q).getList()) {
                        out.put(r.getId(), null);
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static <Q extends PageQuery> Q sized(Q query, int size, Consumer<Q> fill) {
        query.setSize(size);
        fill.accept(query);
        return query;
    }
}
