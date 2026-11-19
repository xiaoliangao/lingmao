package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocNoticeGroup;
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
 * 提醒先按"提醒给谁"取（DocNoticeService），再拿单据 id 问各单据自己的列表筛一遍：模块权限、数据范围、品类线和列表一样，
 * 审核权限不扩大范围
 */
@Component
@RequiredArgsConstructor
class UpstreamRows {

    /** 一次问列表最多几个 id（PageQuery 的 size 上限） */
    private static final int CHUNK = 500;

    private final DocNoticeService noticeService;
    private final ReferenceService referenceService;
    private final ProductionOrderService productionService;
    private final PurchaseOrderService purchaseService;
    private final MrpService mrpService;

    /** 一张下游单据和它没处理的提醒（新的在前）；image 是生产单的款式图、采购单第一行的物料图，算料单没有 */
    record Row(DocRef doc, List<DocNoticeVO> notices, AttachmentVO image) {

        /** 最新一条提醒的时间 */
        OffsetDateTime latest() {
            return notices.getFirst().createTime();
        }
    }

    /**
     * 我有列表权限的那几种单据上没处理的提醒，按最新一条倒序。
     *
     * @param only  只要这一种；null 是全部
     * @param owner 只要提醒给这个人的；null 是所有人的（数据范围"全部"的人没筛负责人时）
     */
    List<Row> rows(BizType only, Long owner) {
        Set<BizType> types = new LinkedHashSet<>();
        if (LoginUser.hasAuthority(HomeServiceImpl.PRODUCTION_LIST)) {
            types.add(BizType.PRODUCTION_ORDER);
        }
        if (LoginUser.hasAuthority(HomeServiceImpl.PURCHASE_LIST)) {
            types.add(BizType.PURCHASE_ORDER);
        }
        if (LoginUser.hasAuthority(HomeServiceImpl.MRP_LIST)) {
            types.add(BizType.MRP_CALC);
        }
        if (only != null) {
            types.retainAll(Set.of(only));
        }
        if (types.isEmpty()) {
            return List.of();
        }
        List<DocNoticeGroup> groups = noticeService.pendingByDoc(types, owner);
        Map<BizType, List<Long>> idsByType = new LinkedHashMap<>();
        groups.forEach(g -> idsByType.computeIfAbsent(g.docType(), k -> new ArrayList<>()).add(g.docId()));
        // 看得见的单据 → 缩略图（可能是 null，所以用 containsKey 判断看不看得见）
        Map<BizType, Map<Long, AttachmentVO>> visible = new HashMap<>();
        Map<BizType, Map<Long, DocRef>> refs = new HashMap<>();
        idsByType.forEach((type, ids) -> {
            Map<Long, AttachmentVO> seen = visible(type, ids);
            visible.put(type, seen);
            refs.put(type, seen.isEmpty() ? Map.of() : referenceService.describe(type, seen.keySet()));
        });
        List<Row> rows = new ArrayList<>();
        for (DocNoticeGroup g : groups) {
            Map<Long, AttachmentVO> seen = visible.get(g.docType());
            if (seen.containsKey(g.docId()) && !g.notices().isEmpty()) {
                rows.add(new Row(refs.get(g.docType()).get(g.docId()), g.notices(), seen.get(g.docId())));
            }
        }
        return rows;
    }

    /** 这几张里我在列表里看得见的（数据范围、品类线），和它们的缩略图 */
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
