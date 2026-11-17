package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.inventory.dto.InvOverRow;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.StockSourceHandler;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/**
 * 采购入库、采购退货的源单这一边（docs/api/04 §3.3、03 §3.3.9）：带出明细、查上限、过账后回写已入库 / 已退货、按到货重算状态。
 * 数量都按采购单位（SourcePosting.Line.purchaseQty）。
 * <ul>
 *   <li>入库：已审核、部分到货的单；每行净入库 + 这次 ≤ 采购数 ×（1 + 5%）（Q09，按净入库算，退了的能补发）</li>
 *   <li>退货：部分到货、已到齐的单；每行这次 ≤ 净入库</li>
 * </ul>
 * check 先锁住采购单（FOR UPDATE），过账、回写都在同一个事务里，两张入库单同时审核时后一张按前一张入完的数查
 */
@Component
@RequiredArgsConstructor
class PurchaseStockHandler implements StockSourceHandler {

    /** 溢收比例 %（Q09，PRD §12 默认）：一期写死，以后要按供应商、物料类型设再挪进配置 */
    static final BigDecimal OVER_PCT = BigDecimal.valueOf(5);

    private static final Set<DocStatus> CAN_RECEIVE = Set.of(APPROVED, RUNNING);
    private static final Set<DocStatus> CAN_RETURN = Set.of(RUNNING, DONE);

    private final PurchaseOrderMapper poMapper;
    private final PurchaseOrderItemMapper itemMapper;
    private final MaterialService materialService;
    private final DictDataService dictDataService;
    private final DocLogService docLogService;

    @Override
    public BizType sourceType() {
        return BizType.PURCHASE_ORDER;
    }

    @Override
    public SourceDraft prefill(InvDocType docType, long sourceId, List<Long> itemIds, Long fromWarehouseId) {
        PurchaseOrder po = poMapper.selectById(sourceId);
        if (po == null) {
            throw new BizException(404, "采购单不存在");
        }
        boolean in = docType == InvDocType.PURCHASE_IN;
        checkStatus(po, in);
        Set<Long> wanted = itemIds == null ? null : new HashSet<>(itemIds);
        List<SourceDraft.Item> items = new ArrayList<>();
        for (PurchaseOrderItem i : itemMapper.selectByOrder(sourceId)) {
            BigDecimal net = i.netReceived();
            BigDecimal max = in ? maxIn(i) : net.max(BigDecimal.ZERO);
            boolean open = in ? i.pending().signum() > 0 : net.signum() > 0;
            if (wanted == null ? !open : !wanted.contains(i.getId())) {
                continue;
            }
            items.add(new SourceDraft.Item(i.getId(), i.getMaterialSkuId(), i.getPurchaseUnit(), i.getConversionFactor(), i.getPrice(),
                    in ? i.pending() : BigDecimal.ZERO, max, i.getQty(), i.getReceivedQty(), i.getReturnedQty(), null, null));
        }
        DocRef source = DocRef.of(BizType.PURCHASE_ORDER, po.getId(), po.getNo(), po.getStatus(), PurchaseOrderServiceImpl.PO_STATUS,
                po.getBuyerId());
        return new SourceDraft(po.getCompanyId(), source, po.getSupplierId(), in ? null : po.getWarehouseId(),
                in ? po.getWarehouseId() : null, items);
    }

    /** 这一行还能入多少：采购数 ×（1 + 5%）− 净入库，不小于 0 */
    static BigDecimal maxIn(PurchaseOrderItem i) {
        return Num.maxQty(i.getQty(), OVER_PCT).subtract(i.netReceived()).max(BigDecimal.ZERO);
    }

    private static void checkStatus(PurchaseOrder po, boolean in) {
        if (!(in ? CAN_RECEIVE : CAN_RETURN).contains(po.getStatus())) {
            throw new BizException("采购单 " + po.getNo() + " 现在是「" + PurchaseOrderServiceImpl.PO_STATUS.label(po.getStatus()).text()
                    + "」，不能" + (in ? "入库" : "退货"));
        }
    }

    @Override
    public void check(SourcePosting posting) {
        PurchaseOrder po = lock(posting.sourceId());
        boolean in = posting.docType() == InvDocType.PURCHASE_IN;
        checkStatus(po, in);
        Map<Long, PurchaseOrderItem> items = itemMapper.selectByOrder(po.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Map<Long, BigDecimal> thisQty = byLine(posting, items, po);
        Map<Long, MaterialSkuBrief> briefs = null;
        Map<String, String> units = null;
        List<InvOverRow> over = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : thisQty.entrySet()) {
            PurchaseOrderItem i = items.get(e.getKey());
            BigDecimal max = in ? maxIn(i) : i.netReceived().max(BigDecimal.ZERO);
            if (e.getValue().compareTo(max) <= 0) {
                continue;
            }
            if (briefs == null) {
                briefs = materialService.skuBriefs(items.values().stream().map(PurchaseOrderItem::getMaterialSkuId).toList());
                units = PurTexts.labels(dictDataService, "unit");
            }
            MaterialSkuBrief b = briefs.get(i.getMaterialSkuId());
            String unit = units.getOrDefault(i.getPurchaseUnit(), i.getPurchaseUnit());
            String reason = in
                    ? "采购 " + PurTexts.plain(i.getQty()) + " " + unit + "，溢收 " + PurTexts.plain(OVER_PCT) + "%，已入库 "
                    + PurTexts.plain(i.getReceivedQty()) + "、已退 " + PurTexts.plain(i.getReturnedQty())
                    : "已入库 " + PurTexts.plain(i.getReceivedQty()) + "、已退 " + PurTexts.plain(i.getReturnedQty());
            over.add(new InvOverRow(seqOf(posting, i.getId()), b == null ? null : b.getSkuCode(), b == null ? null : b.getMaterialName(),
                    po.getNo(), e.getValue(), max, unit, reason));
        }
        if (!over.isEmpty()) {
            throw new BizException(ErrorCodes.OVER_LIMIT, in ? "有 " + over.size() + " 行超收了：最多超收 " + PurTexts.plain(OVER_PCT) + "%"
                    : "有 " + over.size() + " 行退得比入库的多", over);
        }
    }

    /** 这张单每条采购行的数（同一条采购行分几个缸号入的，合起来查）；不是这张采购单的行 400 */
    private static Map<Long, BigDecimal> byLine(SourcePosting posting, Map<Long, PurchaseOrderItem> items, PurchaseOrder po) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (SourcePosting.Line l : posting.lines()) {
            if (l.sourceItemId() == null || !items.containsKey(l.sourceItemId())) {
                throw new BizException(400, "第 " + l.seq() + " 行不是采购单 " + po.getNo() + " 上的物料");
            }
            out.merge(l.sourceItemId(), l.purchaseQty(), BigDecimal::add);
        }
        return out;
    }

    private static int seqOf(SourcePosting posting, long itemId) {
        return posting.lines().stream().filter(l -> Long.valueOf(itemId).equals(l.sourceItemId())).mapToInt(SourcePosting.Line::seq)
                .findFirst().orElse(0);
    }

    @Override
    public void onPosted(SourcePosting posting) {
        apply(posting, false);
    }

    @Override
    public void onUnposted(SourcePosting posting) {
        apply(posting, true);
    }

    /**
     * 回写已入库 / 已退货（反审核就是减回去），再按到货重算状态（已审核、部分到货、已到齐之间换）。
     * 反审核：结案的单不让动（先取消结案）；退过货的入库不能先反审核（净入库会变负），先反审核退货单
     */
    private void apply(SourcePosting posting, boolean reverse) {
        PurchaseOrder po = lock(posting.sourceId());
        boolean in = posting.docType() == InvDocType.PURCHASE_IN;
        if (reverse && po.getStatus() == CLOSED) {
            throw new BizException("采购单 " + po.getNo() + " 已结案：先取消结案再反审核");
        }
        Map<Long, PurchaseOrderItem> items = itemMapper.selectByOrder(po.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Map<Long, BigDecimal> qty = byLine(posting, items, po);
        for (Map.Entry<Long, BigDecimal> e : qty.entrySet()) {
            PurchaseOrderItem i = items.get(e.getKey());
            BigDecimal delta = reverse ? e.getValue().negate() : e.getValue();
            BigDecimal received = in ? i.getReceivedQty().add(delta) : i.getReceivedQty();
            BigDecimal returned = in ? i.getReturnedQty() : i.getReturnedQty().add(delta);
            if (received.subtract(returned).signum() < 0) {
                throw new BizException("采购单 " + po.getNo() + " 第 " + i.getSeq() + " 行入的料已经退了一部分：先反审核退货单，再反审核这张入库单");
            }
            itemMapper.addPosted(i.getId(), in ? delta : BigDecimal.ZERO, in ? BigDecimal.ZERO : delta);
            i.setReceivedQty(received);
            i.setReturnedQty(returned);
        }
        String verb = (reverse ? "反审核" : "") + (in ? "入库单 " : "出库单 ") + posting.docNo();
        docLogService.add(BizType.PURCHASE_ORDER, po.getId(), in ? "receive" : "return", verb);
        if (po.getStatus() == CLOSED) {
            return;
        }
        DocStatus to = PurchaseOrderServiceImpl.byArrival(List.copyOf(items.values()));
        if (to != po.getStatus()) {
            StatusCas.update(poMapper, new PurchaseOrder(), StatusCas.of(PurchaseOrder::getId, po.getId(), PurchaseOrder::getStatus,
                    po.getStatus(), to).setSql("version = version + 1"));
            docLogService.add(BizType.PURCHASE_ORDER, po.getId(), "status", po.getStatus(), to, verb);
        }
    }

    private PurchaseOrder lock(long id) {
        PurchaseOrder po = poMapper.lockById(id);
        if (po == null) {
            throw new BizException(404, "采购单不存在");
        }
        return po;
    }
}
