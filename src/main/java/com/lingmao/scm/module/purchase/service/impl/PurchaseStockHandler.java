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
import com.lingmao.scm.module.inventory.dto.PurchasePostingBasis;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
 *   <li>入库：已审核、部分到货的单，和靠别的单冲来的到齐、自己还没入够的单；每行净入库 + 这次 ≤ 采购数 ×（1 + 5%）
 *       （Q09，按净入库算，退了的能补发；冲入不算进上限，RFC 014 §2）</li>
 *   <li>退货：部分到货、已到齐的单；每行这次 ≤ 净入库</li>
 * </ul>
 * 过账（{@link #preparePosting}）先按 id 从小到大锁住这张采购单和冲抵会动到的同组采购单（FOR UPDATE），回写净入库、冲抵
 * （{@link PurchaseOffsetBook}）、重算这几张单的状态都在同一个事务里，两张入库单同时审核时后一张按前一张入完的数查
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
    private final PurchaseOffsetBook offsetBook;

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
        List<PurchaseOrderItem> lines = itemMapper.selectByOrder(sourceId);
        checkStatus(po, in, lines);
        Set<Long> wanted = itemIds == null ? null : new HashSet<>(itemIds);
        List<SourceDraft.Item> items = new ArrayList<>();
        for (PurchaseOrderItem i : lines) {
            BigDecimal net = i.netReceived();
            BigDecimal max = in ? maxIn(i) : net.max(BigDecimal.ZERO);
            boolean open = in ? i.pending().signum() > 0 || coveredByOffset(i) : net.signum() > 0;
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

    /** 靠冲入到货、自己还没入够采购数的行：供应商照单再送来的照样能入，入了把冲来的还回去（RFC 014 §2） */
    static boolean coveredByOffset(PurchaseOrderItem i) {
        return i.getOffsetInQty() != null && i.getOffsetInQty().signum() > 0 && i.netReceived().compareTo(i.getQty()) < 0;
    }

    /**
     * 状态不让入、不让退抛 1000。已到齐的单有靠冲入到齐的行，还能入库（只能入这种行，见 check）；
     * 只靠冲入变成部分到货、已到齐、自己一行都没入过库的单不能退货
     */
    static void checkStatus(PurchaseOrder po, boolean in, Collection<PurchaseOrderItem> items) {
        boolean ok = in
                ? CAN_RECEIVE.contains(po.getStatus()) || po.getStatus() == DONE && items.stream().anyMatch(PurchaseStockHandler::coveredByOffset)
                : CAN_RETURN.contains(po.getStatus()) && items.stream().anyMatch(i -> i.netReceived().signum() > 0);
        if (!ok) {
            throw new BizException("采购单 " + po.getNo() + " 现在是「" + PurchaseOrderServiceImpl.PO_STATUS.label(po.getStatus()).text()
                    + "」，不能" + (in ? "入库" : "退货"));
        }
    }

    @Override
    public void check(SourcePosting posting) {
        PurchaseOrder po = lock(posting.sourceId());
        boolean in = posting.docType() == InvDocType.PURCHASE_IN;
        Map<Long, PurchaseOrderItem> items = itemMapper.selectByOrder(po.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        checkStatus(po, in, items.values());
        Map<Long, BigDecimal> thisQty = byLine(posting, items, po);
        if (in && po.getStatus() == DONE) {
            // 已到齐的单只能入靠冲入到齐的行；自己入够了的行还有溢收余量也不行（已到齐的单原来就不能再入）
            thisQty.keySet().stream().map(items::get).filter(i -> !coveredByOffset(i)).findFirst().ifPresent(i -> {
                throw new BizException("采购单 " + po.getNo() + " 已到齐，第 " + i.getSeq() + " 行自己已经入够了，不能再入");
            });
        }
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

    /**
     * 正反过账（库存余额锁之前）：先按 id 从小到大锁住会动到的采购单，再查上限（反审核不查）、回写、冲抵、重算状态。
     * 只锁这张单再去锁别的单，两张入库单互相冲时会互相等（RFC 014 §6）
     */
    @Override
    public void preparePosting(SourcePosting posting, boolean reverse) {
        Set<Long> locked = lockAll(posting, reverse);
        if (!reverse) {
            check(posting);
        }
        apply(posting, reverse, locked);
    }

    @Override
    public void onPosted(SourcePosting posting) {
        apply(posting, false, lockAll(posting, false));
    }

    @Override
    public void onUnposted(SourcePosting posting) {
        apply(posting, true, lockAll(posting, true));
    }

    /**
     * 这次过账会动到的采购单，按 id 从小到大一条语句锁上：这一张、这几行冲过的；净入库变多（入库、反审核退货）时加上同组没到齐的。
     * 返回真锁上的：查的时候还在、锁之前被别人删了的草稿不在里面（没冲抵连着它，不冲给它就是了），只有这一张不在才 404
     */
    private Set<Long> lockAll(SourcePosting posting, boolean reverse) {
        boolean growing = (posting.docType() == InvDocType.PURCHASE_IN) != reverse;
        Set<Long> itemIds = posting.lines().stream().map(SourcePosting.Line::sourceItemId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<Long> locked = new TreeSet<>(poMapper.lockForUpdate(offsetBook.poIdsToLock(posting.sourceId(), itemIds, growing)));
        if (!locked.contains(posting.sourceId())) {
            throw new BizException(404, "采购单不存在");
        }
        return locked;
    }

    @Override
    public Map<Long, PurchasePostingBasis> purchasePostingBasis(SourcePosting posting) {
        PurchaseOrder po = lock(posting.sourceId());
        return itemMapper.selectByOrder(po.getId()).stream().collect(Collectors.toMap(PurchaseOrderItem::getId,
                item -> new PurchasePostingBasis(item.getMaterialSkuId(), item.getConversionFactor(), item.getPrice(), item.netReceived())));
    }

    /**
     * 回写已入库 / 已退货（反审核就是减回去），再冲抵（RFC 014：退回、还回、冲），最后按到货重算动到的每张单的状态
     * （已审核、部分到货、已到齐之间换；结案的不动）。
     * 反审核：结案的单不让动（先取消结案）；退过货的入库不能先反审核（净入库会变负），先反审核退货单
     *
     * @param locked 已经锁住的采购单（lockAll）
     */
    private void apply(SourcePosting posting, boolean reverse, Set<Long> locked) {
        PurchaseOrder po = lock(posting.sourceId());
        boolean in = posting.docType() == InvDocType.PURCHASE_IN;
        if (reverse && po.getStatus() == CLOSED) {
            throw new BizException("采购单 " + po.getNo() + " 已结案：先取消结案再反审核");
        }
        Map<Long, PurchaseOrderItem> items = itemMapper.selectByOrder(po.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Map<Long, BigDecimal> qty = byLine(posting, items, po);
        Map<Long, BigDecimal> netDelta = new LinkedHashMap<>();
        for (Map.Entry<Long, BigDecimal> e : qty.entrySet()) {
            PurchaseOrderItem i = items.get(e.getKey());
            BigDecimal delta = reverse ? e.getValue().negate() : e.getValue();
            BigDecimal received = in ? i.getReceivedQty().add(delta) : i.getReceivedQty();
            BigDecimal returned = in ? i.getReturnedQty() : i.getReturnedQty().add(delta);
            if (received.subtract(returned).signum() < 0) {
                throw new BizException("采购单 " + po.getNo() + " 第 " + i.getSeq() + " 行入的料已经退了一部分：先反审核退货单，再反审核这张入库单");
            }
            BigDecimal maxReceived = Num.maxQty(i.getQty(), OVER_PCT);
            if (received.signum() < 0 || returned.signum() < 0 || received.subtract(returned).compareTo(maxReceived) > 0) {
                throw new BizException(ErrorCodes.OVER_LIMIT,
                        "采购单 " + po.getNo() + " 第 " + i.getSeq() + " 行操作后净入库将超过上限：先撤销补收入库，再反审核退货单",
                        List.of(new InvOverRow(seqOf(posting, i.getId()), null, null, po.getNo(),
                                received.subtract(returned), maxReceived, i.getPurchaseUnit(), "采购量 × (1 + 溢收比例)")));
            }
            itemMapper.addPosted(i.getId(), in ? delta : BigDecimal.ZERO, in ? BigDecimal.ZERO : delta);
            netDelta.put(i.getId(), in ? delta : delta.negate());
        }
        String verb = (reverse ? "反审核" : "") + (in ? "入库单 " : "出库单 ") + posting.docNo();
        docLogService.add(BizType.PURCHASE_ORDER, po.getId(), in ? "receive" : "return", verb);
        Set<Long> touched = new TreeSet<>(offsetBook.settle(netDelta, locked, verb).poIds());
        touched.add(po.getId());
        for (long id : touched) {
            refreshStatus(id == po.getId() ? po : poMapper.lockById(id), verb);
        }
    }

    /** 按到货重算一张单的状态（已锁住）；结案的不动 */
    private void refreshStatus(PurchaseOrder po, String verb) {
        if (po.getStatus() == CLOSED) {
            return;
        }
        DocStatus to = PurchaseOrderServiceImpl.byArrival(itemMapper.selectByOrder(po.getId()));
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
