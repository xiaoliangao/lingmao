package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcTransitMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 算料采购这边的单据怎么出现在别处（README §4.3"下游单据"、1005 的清单、变更提醒）：
 * <ul>
 *   <li>订单的下游：算着它的、没关闭的算料单（草稿、已确认），和这些算料单生成的采购单（没作废的）。
 *       订单反审核、取消被它们拦；订单变更审过以后给它们各插一条变更提醒（api/02 §3.12）</li>
 *   <li>算料单的下游：它生成的采购单（没作废的）</li>
 *   <li>采购单的下游：把它用作本次使用在途的已确认算料单（入库单、出库单由 InventoryReferrers 给）</li>
 * </ul>
 * 算料单的负责人是制单人，采购单的是采购员
 */
@Component
@RequiredArgsConstructor
class PurchaseReferrers implements ReferrerProvider {

    private static final Set<String> OPEN_CALC = Set.of(MrpStatus.DRAFT.name(), MrpStatus.CONFIRMED.name());

    private final MrpCalcMapper calcMapper;
    private final MrpCalcTransitMapper transitMapper;
    private final PurchaseOrderMapper poMapper;
    private final PurchaseOrderItemMapper itemMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return switch (type) {
            case ORDER -> ofOrder(id);
            case MRP_CALC -> poMapper.selectByCalc(id).stream().map(PurchaseReferrers::ref).toList();
            case PURCHASE_ORDER -> usersOf(itemMapper.selectByOrder(id).stream().map(PurchaseOrderItem::getId).toList());
            default -> List.of();
        };
    }

    private List<DocRef> ofOrder(long orderId) {
        List<DocRef> out = new ArrayList<>();
        Map<Long, DocRef> pos = new LinkedHashMap<>();
        for (MrpCalcMapper.OrderCalc c : calcMapper.selectOrderCalcs(List.of(orderId))) {
            if (!OPEN_CALC.contains(c.status())) {
                continue;
            }
            out.add(DocRef.of(BizType.MRP_CALC, c.calcId(), c.calcNo(), MrpStatus.valueOf(c.status()), MrpServiceImpl.MRP_STATUS,
                    c.createBy()));
            poMapper.selectByCalc(c.calcId()).forEach(po -> pos.putIfAbsent(po.getId(), ref(po)));
        }
        out.addAll(pos.values());
        return out;
    }

    private List<DocRef> usersOf(List<Long> lineIds) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        Map<Long, DocRef> out = new LinkedHashMap<>();
        transitMapper.selectLineUsers(lineIds).forEach(u -> out.putIfAbsent(u.calcId(), DocRef.of(BizType.MRP_CALC, u.calcId(),
                u.calcNo(), MrpStatus.valueOf(u.status()), MrpServiceImpl.MRP_STATUS, u.createBy())));
        return List.copyOf(out.values());
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case MRP_CALC -> calcMapper.selectByIds(ids).stream().collect(Collectors.toMap(MrpCalc::getId,
                    c -> DocRef.of(BizType.MRP_CALC, c.getId(), c.getNo(), c.getStatus(), MrpServiceImpl.MRP_STATUS, c.getCreateBy())));
            case PURCHASE_ORDER -> poMapper.selectByIds(ids).stream().collect(Collectors.toMap(PurchaseOrder::getId, PurchaseReferrers::ref));
            default -> Map.of();
        };
    }

    private static DocRef ref(PurchaseOrder po) {
        return DocRef.of(BizType.PURCHASE_ORDER, po.getId(), po.getNo(), po.getStatus(), PurchaseOrderServiceImpl.PO_STATUS, po.getBuyerId());
    }
}
