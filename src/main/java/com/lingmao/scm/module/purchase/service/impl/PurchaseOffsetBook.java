package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.purchase.mapper.PurchaseOffsetMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOffsetMapper.LineRow;
import com.lingmao.scm.module.purchase.mapper.PurchaseOffsetMapper.OffsetRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 采购超收冲抵的取数、加锁、写回（RFC 014 §5）；怎么冲在 {@link OverOffsetPlan}。都在入库单、出库单过账的事务里调：
 * 先 {@link #poIdsToLock} 列出要动的采购单，由 {@link PurchaseStockHandler} 按 id 从小到大一次锁住；回写净入库以后再 {@link #settle}
 */
@Component
@RequiredArgsConstructor
class PurchaseOffsetBook {

    private static final Set<String> OPEN = Set.of(DocStatus.APPROVED.name(), DocStatus.RUNNING.name());

    private final PurchaseOffsetMapper mapper;
    private final DocLogService docLogService;

    /** 冲完以后：每行冲入、冲出变了多少，和这些行所在的采购单（要重算状态） */
    record Settled(Map<Long, BigDecimal> inDelta, Map<Long, BigDecimal> outDelta, Set<Long> poIds) {
        static final Settled NONE = new Settled(Map.of(), Map.of(), Set.of());
    }

    /**
     * 过账会动到的采购单（还没锁）：这一张、和这几行冲过的、净入库会变多时同组没到齐的
     *
     * @param growing 入库、反审核退货（净入库变多，可能要冲给别的单）
     */
    Set<Long> poIdsToLock(long poId, Collection<Long> itemIds, boolean growing) {
        Set<Long> ids = new TreeSet<>();
        ids.add(poId);
        if (itemIds.isEmpty()) {
            return ids;
        }
        mapper.selectOfItems(itemIds).forEach(o -> {
            ids.add(o.fromPoId());
            ids.add(o.toPoId());
        });
        if (growing) {
            mapper.selectOpenInGroups(itemIds).forEach(l -> ids.add(l.poId()));
        }
        return ids;
    }

    /**
     * 回写净入库以后算冲抵、写回、记操作记录（两张单各记一条）。
     *
     * @param posted 这次过账每行净入库变了多少（带正负）
     * @param locked 已经锁住的采购单（{@link #poIdsToLock} 列出来、按 id 顺序锁上的）。锁之前没查到的单不在里面：
     *               要退回、还回的冲抵连着这种单就报 1002 让他重试；同组新冒出来没到齐的单不冲给它
     * @param verb   操作记录里写"入库单 RK…""反审核出库单 CK…"
     */
    Settled settle(Map<Long, BigDecimal> posted, Set<Long> locked, String verb) {
        if (posted.isEmpty()) {
            return Settled.NONE;
        }
        boolean growing = posted.values().stream().anyMatch(v -> v.signum() > 0);
        List<OffsetRow> offsets = mapper.selectOfItems(posted.keySet());
        Set<Long> offsetPos = new HashSet<>();
        Set<Long> lineIds = new HashSet<>(posted.keySet());
        offsets.forEach(o -> {
            offsetPos.add(o.fromPoId());
            offsetPos.add(o.toPoId());
            lineIds.add(o.fromItemId());
            lineIds.add(o.toItemId());
        });
        if (!locked.containsAll(offsetPos)) {
            // 锁之前没查到、锁的时候别人刚冲上的：不在锁里的单不能写，让他重试
            throw new BizException(ErrorCodes.STATE_CHANGED, "这几张采购单刚被别人入库、退货改过，请重试");
        }
        Map<Long, LineRow> lines = new LinkedHashMap<>();
        mapper.selectLines(lineIds).forEach(l -> lines.put(l.id(), l));
        if (growing) {
            mapper.selectOpenInGroups(posted.keySet()).stream().filter(l -> locked.contains(l.poId()))
                    .forEach(l -> lines.putIfAbsent(l.id(), l));
        }
        OverOffsetPlan.Result r = OverOffsetPlan.plan(lines.values().stream().map(PurchaseOffsetBook::line).toList(),
                offsets.stream().map(o -> new OverOffsetPlan.Offset(o.id(), o.fromItemId(), o.toItemId(), o.qty(), o.createTime()))
                        .toList(), posted);
        r.updated().forEach((id, qty) -> {
            if (qty.signum() == 0) {
                mapper.delete(id);
            } else {
                mapper.updateQty(id, qty);
            }
        });
        r.added().forEach(m -> mapper.insert(m.fromId(), m.toId(), m.qty()));
        Set<Long> touched = r.touchedLines();
        touched.stream().sorted().forEach(id -> mapper.addOffset(id, r.inDelta().getOrDefault(id, BigDecimal.ZERO),
                r.outDelta().getOrDefault(id, BigDecimal.ZERO)));
        r.added().forEach(m -> log(lines.get(m.fromId()), lines.get(m.toId()), m.qty(), false, verb));
        r.undone().forEach(m -> log(lines.get(m.fromId()), lines.get(m.toId()), m.qty(), true, verb));
        return new Settled(r.inDelta(), r.outDelta(), touched.stream().map(id -> lines.get(id).poId()).collect(Collectors.toSet()));
    }

    /** 两张单各记一条；同一张单上两行之间冲的只记一条 */
    private void log(LineRow from, LineRow to, BigDecimal qty, boolean undo, String verb) {
        String q = PurTexts.plain(qty);
        if (from.poId().equals(to.poId())) {
            docLogService.add(BizType.PURCHASE_ORDER, from.poId(), "offset", undo
                    ? "第 " + from.seq() + " 行冲给第 " + to.seq() + " 行的 " + q + " 退回（" + verb + "）"
                    : verb + "：第 " + from.seq() + " 行多收 " + q + "，冲给第 " + to.seq() + " 行");
            return;
        }
        docLogService.add(BizType.PURCHASE_ORDER, from.poId(), "offset", undo
                ? "冲给 " + to.poNo() + " 的 " + q + " 退回（" + verb + "）"
                : verb + "：第 " + from.seq() + " 行多收 " + q + "，冲给 " + to.poNo());
        docLogService.add(BizType.PURCHASE_ORDER, to.poId(), "offset", undo
                ? from.poNo() + " 冲来的 " + q + " 退回（" + verb + "）"
                : from.poNo() + " 多收，冲来 " + q + "（" + verb + "）");
    }

    private static OverOffsetPlan.Line line(LineRow l) {
        return new OverOffsetPlan.Line(l.id(), l.poId(), l.poNo(), l.groupKey(), l.orderDate(), l.seq(), l.auditTime(),
                OPEN.contains(l.poStatus()), l.qty(), l.receivedQty().subtract(l.returnedQty()), l.offsetInQty(), l.offsetOutQty());
    }
}
