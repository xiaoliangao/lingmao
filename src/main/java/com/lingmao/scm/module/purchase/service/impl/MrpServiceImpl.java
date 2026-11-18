package com.lingmao.scm.module.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.purchase.dto.MrpCalcDetailVO;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcRowVO;
import com.lingmao.scm.module.purchase.dto.MrpCreateReq;
import com.lingmao.scm.module.purchase.dto.MrpDiffRowVO;
import com.lingmao.scm.module.purchase.dto.MrpItemForm;
import com.lingmao.scm.module.purchase.dto.MrpItemVO;
import com.lingmao.scm.module.purchase.dto.MrpLateTransitVO;
import com.lingmao.scm.module.purchase.dto.MrpOrderOptionVO;
import com.lingmao.scm.module.purchase.dto.MrpOrderRef;
import com.lingmao.scm.module.purchase.dto.MrpTransitUseVO;
import com.lingmao.scm.module.purchase.dto.OrderKittingVO;
import com.lingmao.scm.module.purchase.dto.RemarkReq;
import com.lingmao.scm.module.purchase.dto.ReservePctReq;
import com.lingmao.scm.module.purchase.dto.ReservePctVO;
import com.lingmao.scm.module.purchase.entity.MrpBlock;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.module.purchase.entity.MrpCalcItem;
import com.lingmao.scm.module.purchase.entity.MrpCalcTransit;
import com.lingmao.scm.module.purchase.entity.MrpCloseReason;
import com.lingmao.scm.module.purchase.entity.MrpSnapshot;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.mapper.MrpCalcItemMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcTransitMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.lingmao.scm.module.purchase.entity.MrpStatus.CLOSED;
import static com.lingmao.scm.module.purchase.entity.MrpStatus.CONFIRMED;
import static com.lingmao.scm.module.purchase.entity.MrpStatus.DRAFT;

/**
 * 算料单（docs/api/03 §3.2）。算由 MrpEngine 取数、MrpCalculator 算；这里管状态、软占用的边界和详情里现取的数。
 * <p>
 * 软占用（架构 §5.4）：已确认、没关闭的算料单的本次使用库存、本次使用在途（mrp_calc_transit）、它生成的采购行都算占着，
 * 别的单算可用量时扣掉（SupplyBook）。草稿不占：确认时按最新的数再算一遍，对不上就不让确认（"可用量已变化"）。
 * <p>
 * 重新算料（§3.2.9）：草稿就地重算；已确认的另算一张草稿（prevCalcId 指着它），新单不把上一张的占用当"别人的"，
 * 上一张自己生成的采购行也不当可用的（确认时整条转过来，见 SupplyBook 类注释）。新单确认时上一张关闭（RECALC）。
 * <p>
 * 锁的顺序：算料单（按 id 从小到大）→ 物料 SKU 的咨询锁 → 采购单。采购单改、删、作废时先锁它的算料单再锁采购单（lockCalcsOfItems），
 * 免得两边互相等
 */
@Service
@RequiredArgsConstructor
public class MrpServiceImpl implements MrpService {

    /**
     * 状态转换表（docs/api/03 §2.1）。edit 改行（草稿四格，已确认只能换供应商）；toPurchase 生成采购单（草稿先确认）；
     * recalc 草稿就地重算、已确认另算新单，自己的状态不变；removeItem 删行（草稿），不出按钮
     */
    static final StatusFlow<MrpStatus> FLOW = StatusFlow.of(MrpStatus.class)
            .on("edit", DRAFT, CONFIRMED).to(DRAFT, CONFIRMED)
            .on("confirm", DRAFT).to(CONFIRMED)
            .on("toPurchase", DRAFT, CONFIRMED).to(CONFIRMED)
            .on("recalc", DRAFT, CONFIRMED).to(DRAFT, CONFIRMED)
            .on("close", CONFIRMED).to(CLOSED)
            .on("remove", DRAFT).to(DRAFT)
            .on("removeItem", DRAFT).to(DRAFT)
            .build();

    static final String PERM = "purchase:mrp:";

    /** 和前端 types/purchase.ts 的 MRP_STATUS 一样 */
    static final StatusLabels<MrpStatus> MRP_STATUS = StatusLabels.of(MrpStatus.class)
            .with(DRAFT, "草稿", StatusTone.OFF)
            .with(CONFIRMED, "已确认", StatusTone.ACTIVE)
            .with(CLOSED, "已关闭", StatusTone.CLOSED);

    static final String STALE_SUPPLY = "可用量已变化，请重新计算";

    private static final String NOT_FOUND = "算料单不存在";
    private static final String STALE = "这张算料单已经被别人改过，请刷新后再改";

    private final MrpCalcMapper calcMapper;
    private final MrpCalcItemMapper itemMapper;
    private final MrpCalcTransitMapper transitMapper;
    private final PurchaseOrderMapper poMapper;
    private final PurchaseOrderItemMapper poItemMapper;
    private final MrpEngine engine;
    private final SupplyLoader supplyLoader;
    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final DocNoticeService docNoticeService;
    private final AttachmentService attachmentService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;

    // ---------- 查询 ----------

    @Override
    public PageResult<MrpCalcRowVO> page(MrpCalcQuery query) {
        IPage<MrpCalcRowVO> page = calcMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
        List<MrpCalcRowVO> rows = page.getRecords();
        if (!rows.isEmpty()) {
            Map<Long, List<MrpCalcMapper.CalcOrder>> orders = calcOrders(rows.stream().map(MrpCalcRowVO::getId).toList());
            Map<Long, List<String>> styles = orderStyles(orders.values().stream().flatMap(List::stream)
                    .map(MrpCalcMapper.CalcOrder::orderId).collect(Collectors.toSet()));
            Map<Long, String> names = userService.nicknames(rows.stream().map(MrpCalcRowVO::getCreateBy)
                    .filter(Objects::nonNull).collect(Collectors.toSet()));
            for (MrpCalcRowVO r : rows) {
                List<MrpCalcMapper.CalcOrder> os = orders.getOrDefault(r.getId(), List.of());
                r.setOrderNos(os.stream().map(MrpCalcMapper.CalcOrder::orderNo).toList());
                r.setStyleCodes(styleCodes(os, styles));
                r.setCreateByName(r.getCreateBy() == null ? null : names.get(r.getCreateBy()));
            }
        }
        return PageResult.of(page);
    }

    private Map<Long, List<MrpCalcMapper.CalcOrder>> calcOrders(Collection<Long> calcIds) {
        return calcMapper.selectCalcOrders(calcIds).stream()
                .collect(Collectors.groupingBy(MrpCalcMapper.CalcOrder::calcId, LinkedHashMap::new, Collectors.toList()));
    }

    /** 订单 → 款号（按订单行的顺序，去重） */
    private Map<Long, List<String>> orderStyles(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return calcMapper.selectOrderStyles(orderIds).stream().collect(Collectors.groupingBy(MrpCalcMapper.OrderStyle::orderId,
                Collectors.mapping(MrpCalcMapper.OrderStyle::styleCode, Collectors.toList())));
    }

    private static List<String> styleCodes(List<MrpCalcMapper.CalcOrder> orders, Map<Long, List<String>> styles) {
        Set<String> codes = new LinkedHashSet<>();
        orders.forEach(o -> codes.addAll(styles.getOrDefault(o.orderId(), List.of())));
        return List.copyOf(codes);
    }

    @Override
    public MrpCalcDetailVO detail(long id) {
        MrpCalc c = getVisible(id);
        List<MrpCalcItem> items = itemMapper.selectByCalc(id);
        List<MrpCalcTransit> uses = transitMapper.selectByCalc(id);
        List<MrpCalcMapper.CalcOrder> orders = calcOrders(List.of(id)).getOrDefault(id, List.of());
        Map<Long, List<String>> styles = orderStyles(orders.stream().map(MrpCalcMapper.CalcOrder::orderId).toList());

        MrpCalcDetailVO vo = docHeaderService.fill(new MrpCalcDetailVO(), BizType.MRP_CALC, c, c.getStatus());
        vo.setOrders(orders.stream().map(o -> new MrpOrderRef(o.orderId(), o.orderNo(), styles.getOrDefault(o.orderId(), List.of()))).toList());
        vo.setStyleCodes(styleCodes(orders, styles));
        vo.setNeedDate(c.getNeedDate());
        vo.setPrevCalcId(c.getPrevCalcId());
        if (c.getPrevCalcId() != null) {
            vo.setPrevCalcNo(calcMapper.selectById(c.getPrevCalcId()).getNo());
        }
        MrpCalc next = calcMapper.selectNext(id);
        vo.setNextCalcId(next == null ? null : next.getId());
        vo.setNextCalcNo(next == null ? null : next.getNo());
        vo.setCloseReason(c.getCloseReason() == null ? null : c.getCloseReason().name());
        vo.setCloseByName(c.getCloseBy() == null ? null : userService.nicknames(List.of(c.getCloseBy())).get(c.getCloseBy()));
        vo.setCloseTime(c.getCloseTime());

        boolean nextDraft = next != null && next.getStatus() == DRAFT;
        Progress p = progress(items, uses, c.getStatus() == CONFIRMED ? Set.of(id) : Set.of());
        vo.setItems(itemVOs(c, items, uses, p, nextDraft));
        List<MrpCalcItem> buy = items.stream().filter(i -> i.getBlock() == MrpBlock.PURCHASE).toList();
        vo.setEstAmount(Num.money(SupplyBook.sum(buy.stream().map(MrpCalcItem::getEstAmount).toList())));
        vo.setConvertedAmount(Num.money(SupplyBook.sum(buy.stream()
                .map(i -> i.getRefPrice() == null ? null : i.getConvertedQty().multiply(i.getRefPrice())).toList())));
        vo.setDiff(c.getPrevCalcId() == null ? null : diff(itemMapper.selectByCalc(c.getPrevCalcId()), items));
        vo.setActions(actions(c.getStatus(), new Ctx(nextDraft, convertible(items))));
        return vo;
    }

    /** 还有没转完的行（我方采购、建议采购 > 已转采购） */
    private static boolean convertible(List<MrpCalcItem> items) {
        return items.stream().anyMatch(MrpServiceImpl::convertibleRow);
    }

    private static boolean convertibleRow(MrpCalcItem i) {
        return i.getBlock() == MrpBlock.PURCHASE && i.getSuggestQty().compareTo(i.getConvertedQty()) > 0;
    }

    /** 算按钮要的业务条件：nextDraft 有重新算料的草稿（不能改、关、再算、生成采购单），convertible 还有没转完的行 */
    record Ctx(boolean nextDraft, boolean convertible) {
    }

    /** 按钮（前端 MrpAction）。确认、关闭看 purchase:mrp:edit，重新算料看 :add，生成采购单看 purchase:order:add */
    static List<String> actions(MrpStatus status, Ctx c) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("confirm", PERM + "edit")
                .perm("close", PERM + "edit")
                .perm("recalc", PERM + "add")
                .perm("remove", PERM + "remove")
                .perm("toPurchase", "purchase:order:add")
                .when("edit", !c.nextDraft() && (status == DRAFT || c.convertible()))
                .when("close", !c.nextDraft())
                .when("recalc", !c.nextDraft())
                .when("toPurchase", !c.nextDraft() && c.convertible())
                .list();
    }

    /**
     * 详情、齐料现取的数：每行生成的采购行净入库（库存单位，连同重新算料转过来的），本次使用在途里已经到的。
     * 已到的只有已确认的算料单才算（activeCalcIds 里的）：草稿、已关闭的不占，按 0
     */
    record Progress(Map<Long, BigDecimal> received, Map<Long, BigDecimal> arrived) {

        BigDecimal receivedOf(long itemId) {
            return received.getOrDefault(itemId, BigDecimal.ZERO);
        }

        BigDecimal arrivedOf(long useId) {
            return arrived.getOrDefault(useId, BigDecimal.ZERO);
        }

        /** 齐料还差 = 需求 − 本次使用库存 − 用的在途里已到的 − 已入库，不小于 0；工厂自备的是 0 */
        BigDecimal shortOf(MrpCalcItem i, List<MrpCalcTransit> uses) {
            if (i.getBlock() != MrpBlock.PURCHASE) {
                return BigDecimal.ZERO;
            }
            BigDecimal arrivedSum = SupplyBook.sum(uses.stream().map(u -> arrivedOf(u.getId())).toList());
            return i.getNeedQty().subtract(i.getUseStockQty()).subtract(arrivedSum).subtract(receivedOf(i.getId()))
                    .max(BigDecimal.ZERO);
        }
    }

    private Progress progress(List<MrpCalcItem> items, List<MrpCalcTransit> uses, Set<Long> activeCalcIds) {
        Map<Long, BigDecimal> received = new HashMap<>();
        List<Long> buyIds = items.stream().filter(i -> i.getBlock() == MrpBlock.PURCHASE).map(MrpCalcItem::getId).toList();
        if (!buyIds.isEmpty()) {
            for (PurchaseOrderItem l : poItemMapper.selectByCalcItems(buyIds)) {
                received.merge(l.getCalcItemId(), Num.qty(l.netReceived().max(BigDecimal.ZERO).multiply(l.getConversionFactor())),
                        BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> arrived = new HashMap<>();
        if (!activeCalcIds.isEmpty() && !uses.isEmpty()) {
            Set<Long> skuIds = items.stream().map(MrpCalcItem::getMaterialSkuId).collect(Collectors.toSet());
            SupplyBook book = supplyLoader.load(skuIds, activeCalcIds);
            uses.forEach(u -> arrived.put(u.getId(), book.arrivedOf(u.getId())));
        }
        return new Progress(received, arrived);
    }

    private List<MrpItemVO> itemVOs(MrpCalc c, List<MrpCalcItem> items, List<MrpCalcTransit> uses, Progress p, boolean nextDraft) {
        Set<Long> skuIds = items.stream().map(MrpCalcItem::getMaterialSkuId).collect(Collectors.toSet());
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(skuIds);
        Map<Long, SupplierRef> suppliers = supplierService.refs(items.stream().map(MrpCalcItem::getSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, List<MrpCalcTransit>> usesByItem = uses.stream().collect(Collectors.groupingBy(MrpCalcTransit::getCalcItemId));
        List<MrpSnapshot.Late> lates = c.getInputSnapshot() == null || c.getInputSnapshot().lates() == null ? List.of()
                : c.getInputSnapshot().lates();
        Set<Long> lineIds = new HashSet<>();
        uses.forEach(u -> lineIds.add(u.getPurchaseItemId()));
        lates.forEach(l -> lineIds.add(l.purchaseItemId()));
        Map<Long, PurchaseOrderItemMapper.LineRef> refs = lineIds.isEmpty() ? Map.of()
                : poItemMapper.selectLineRefs(lineIds).stream().collect(Collectors.toMap(PurchaseOrderItemMapper.LineRef::id, r -> r));
        boolean open = c.getStatus() != CLOSED && !nextDraft;

        List<MrpItemVO> out = new ArrayList<>();
        for (MrpCalcItem i : items) {
            MrpItemVO v = new MrpItemVO();
            v.fillFrom(briefs.get(i.getMaterialSkuId()));
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setBlock(i.getBlock().name());
            v.setSupplierId(i.getSupplierId());
            SupplierRef s = i.getSupplierId() == null ? null : suppliers.get(i.getSupplierId());
            v.setSupplierName(s == null ? null : s.shortName());
            v.setSupplierStatus(s == null || s.status() == null ? null : s.status().name());
            v.setGrossQty(i.getGrossQty());
            v.setReservePct(i.getReservePct());
            v.setNeedQty(i.getNeedQty());
            v.setAvailStockQty(i.getAvailStockQty());
            v.setUseStockQty(i.getUseStockQty());
            v.setAvailTransitQty(i.getAvailTransitQty());
            v.setUseTransitQty(i.getUseTransitQty());
            v.setLateTransitQty(i.getLateTransitQty());
            v.setNetQty(i.getNetQty());
            v.setPurchaseUnit(i.getPurchaseUnit());
            v.setConversionFactor(i.getConversionFactor());
            v.setSuggestQty(i.getSuggestQty());
            v.setRefPrice(i.getRefPrice());
            v.setEstAmount(i.getEstAmount());
            v.setConvertedQty(i.getConvertedQty());
            v.setReceivedQty(i.getBlock() == MrpBlock.PURCHASE ? p.receivedOf(i.getId()) : BigDecimal.ZERO);
            List<MrpCalcTransit> mine = usesByItem.getOrDefault(i.getId(), List.of());
            v.setShortQty(p.shortOf(i, mine));
            v.setOrderNos(PurTexts.split(i.getOrderNos()));
            v.setStyleCodes(PurTexts.split(i.getStyleCodes()));
            v.setTransitUses(mine.stream().filter(u -> refs.containsKey(u.getPurchaseItemId())).map(u -> {
                PurchaseOrderItemMapper.LineRef r = refs.get(u.getPurchaseItemId());
                return new MrpTransitUseVO(u.getPurchaseItemId(), r.poId(), r.poNo(), r.purchaseType(), u.getEta(), u.getQty(),
                        p.arrivedOf(u.getId()));
            }).toList());
            v.setLateTransits(i.getBlock() != MrpBlock.PURCHASE ? List.of() : lates.stream()
                    .filter(l -> l.skuId() == i.getMaterialSkuId() && refs.containsKey(l.purchaseItemId())).map(l -> {
                        PurchaseOrderItemMapper.LineRef r = refs.get(l.purchaseItemId());
                        return new MrpLateTransitVO(l.purchaseItemId(), r.poId(), r.poNo(), r.purchaseType(), l.eta(), l.freeQty());
                    }).toList());
            v.setCanConvert(open && convertibleRow(i));
            out.add(v);
        }
        return out;
    }

    /** 和上一张比需求变了的行：按（块，SKU）对，没有的当 0 */
    private List<MrpDiffRowVO> diff(List<MrpCalcItem> from, List<MrpCalcItem> to) {
        Map<String, MrpCalcItem> before = from.stream().collect(Collectors.toMap(MrpServiceImpl::key, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<String, MrpCalcItem> after = to.stream().collect(Collectors.toMap(MrpServiceImpl::key, i -> i, (a, b) -> a, LinkedHashMap::new));
        Set<String> keys = new LinkedHashSet<>(after.keySet());
        keys.addAll(before.keySet());
        Set<Long> skuIds = new HashSet<>();
        from.forEach(i -> skuIds.add(i.getMaterialSkuId()));
        to.forEach(i -> skuIds.add(i.getMaterialSkuId()));
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(skuIds);
        List<MrpDiffRowVO> out = new ArrayList<>();
        for (String k : keys) {
            MrpCalcItem a = before.get(k);
            MrpCalcItem b = after.get(k);
            BigDecimal fromQty = a == null ? BigDecimal.ZERO : a.getNeedQty();
            BigDecimal toQty = b == null ? BigDecimal.ZERO : b.getNeedQty();
            if (fromQty.compareTo(toQty) == 0) {
                continue;
            }
            MrpCalcItem any = b != null ? b : a;
            MrpDiffRowVO v = new MrpDiffRowVO();
            v.fillFrom(briefs.get(any.getMaterialSkuId()));
            v.setBlock(any.getBlock().name());
            v.setFromNeedQty(fromQty);
            v.setToNeedQty(toQty);
            v.setDiffQty(toQty.subtract(fromQty));
            out.add(v);
        }
        return out;
    }

    private static String key(MrpCalcItem i) {
        return i.getBlock() + ":" + i.getMaterialSkuId();
    }

    @Override
    public void checkSourceOrdersVisible(Collection<Long> ids) { engine.checkSourceVisible(ids); }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    @Override
    public MrpCalc getById(long id) {
        MrpCalc c = calcMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return c;
    }

    private MrpCalc getVisible(long id) {
        return visible(calcMapper.selectById(id));
    }

    /** 数据范围（PRD §3.2）：本人范围的看制单人是自己的 */
    private MrpCalc visible(MrpCalc c) {
        if (c == null || !DataScope.current(false).canSee(c.getCreateBy())) {
            throw new BizException(404, NOT_FOUND);
        }
        engine.checkSourceVisible(calcOrders(List.of(c.getId())).getOrDefault(c.getId(), List.of()).stream()
                .map(MrpCalcMapper.CalcOrder::orderId).toList());
        return c;
    }

    /** 改、删、动作之前锁住单头 */
    private MrpCalc lock(long id) {
        return visible(calcMapper.lockById(id));
    }

    /** 确认要连上一张一起锁：按 id 从小到大锁（上一张的 id 小），和采购单那边的顺序一样 */
    private MrpCalc lockWithPrev(long id) {
        MrpCalc peek = getVisible(id);
        if (peek.getPrevCalcId() != null) {
            calcMapper.lockById(peek.getPrevCalcId());
        }
        return lock(id);
    }

    private static void checkVersion(MrpCalc c, Integer version) {
        if (!Objects.equals(c.getVersion(), version)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
    }

    /** 版本号 +1（比较并交换）：改行、删行、改备注 */
    private void bumpVersion(MrpCalc c) {
        if (calcMapper.update(new MrpCalc(), new LambdaUpdateWrapper<MrpCalc>()
                .setSql("version = version + 1")
                .eq(MrpCalc::getId, c.getId())
                .eq(MrpCalc::getVersion, c.getVersion())) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
    }

    private MrpCalc nextDraft(MrpCalc c) {
        MrpCalc next = calcMapper.selectNext(c.getId());
        return next != null && next.getStatus() == DRAFT ? next : null;
    }

    @Override
    public PageResult<MrpOrderOptionVO> orderOptions(String keyword, PageQuery page) {
        IPage<MrpOrderOptionVO> result = calcMapper.selectOrderOptions(page.toPage(), keyword, Keywords.codes(keyword));
        List<MrpOrderOptionVO> rows = result.getRecords();
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(MrpOrderOptionVO::getOrderId).toList();
            Map<Long, List<String>> styles = orderStyles(ids);
            Map<Long, List<MrpOrderOptionVO.CalcRef>> calcs = new HashMap<>();
            calcMapper.selectOrderCalcs(ids).forEach(c -> calcs.computeIfAbsent(c.orderId(), k -> new ArrayList<>())
                    .add(new MrpOrderOptionVO.CalcRef(c.calcId(), c.calcNo(), c.status())));
            rows.forEach(r -> {
                r.setStyleCodes(styles.getOrDefault(r.getOrderId(), List.of()));
                r.setCalcs(calcs.getOrDefault(r.getOrderId(), List.of()));
            });
        }
        return PageResult.of(result);
    }

    // ---------- 算料、重新算料 ----------

    @Override
    @Transactional
    public DocSaved create(MrpCreateReq req) {
        List<MrpCalcMapper.OrderHead> heads = engine.checkedHeads(req.getOrderIds());
        MrpEngine.Result r = engine.compute(heads, Set.of(), null);
        MrpCalc c = new MrpCalc();
        c.setNo(serialNoGenerator.daily(BizType.MRP_CALC.getPrefix()));
        c.setStatus(DRAFT);
        c.setNeedDate(r.needDate());
        c.setInputSnapshot(r.snapshot());
        calcMapper.insert(c);
        calcMapper.insertOrders(c.getId(), heads.stream().map(MrpCalcMapper.OrderHead::id).toList());
        insertRows(c.getId(), r.rows());
        docLogService.add(BizType.MRP_CALC, c.getId(), "create",
                "订单 " + heads.stream().map(MrpCalcMapper.OrderHead::no).collect(Collectors.joining("、")));
        return new DocSaved(c.getId(), c.getNo(), 0);
    }

    /** 结果行和它们分到的在途存进去 */
    private void insertRows(long calcId, List<MrpCalculator.Row> rows) {
        for (MrpCalculator.Row row : rows) {
            MrpCalcItem item = row.item();
            item.setCalcId(calcId);
            itemMapper.insert(item);
            for (MrpCalculator.Alloc a : row.allocs()) {
                transitMapper.insert(transit(item.getId(), a.lineId(), a.qty(), a.eta()));
            }
        }
    }

    private static MrpCalcTransit transit(long itemId, long lineId, BigDecimal qty, LocalDate eta) {
        MrpCalcTransit t = new MrpCalcTransit();
        t.setCalcItemId(itemId);
        t.setPurchaseItemId(lineId);
        t.setQty(qty);
        t.setEta(eta);
        return t;
    }

    /** 不算"别人"的：自己和上一张 */
    private static Set<Long> excludedOf(MrpCalc c) {
        Set<Long> out = new HashSet<>();
        if (c.getId() != null) {
            out.add(c.getId());
        }
        if (c.getPrevCalcId() != null) {
            out.add(c.getPrevCalcId());
        }
        return out;
    }

    @Override
    @Transactional
    public DocSaved recalc(long id, DocActionReq req) {
        MrpCalc c = lock(id);
        FLOW.next("recalc", c.getStatus(), c.getStatus());
        checkVersion(c, req.version());
        List<Long> orderIds = calcOrders(List.of(id)).getOrDefault(id, List.of()).stream().map(MrpCalcMapper.CalcOrder::orderId).toList();
        if (c.getStatus() == DRAFT) {
            List<MrpCalcMapper.OrderHead> heads = engine.checkedHeads(orderIds);
            MrpEngine.Result r = engine.compute(heads, excludedOf(c), c.getPrevCalcId());
            transitMapper.deleteByCalc(id);
            itemMapper.deleteByCalc(id);
            insertRows(id, r.rows());
            c.setNeedDate(r.needDate());
            c.setInputSnapshot(r.snapshot());
            if (calcMapper.update(c, Updates.replaceById(c)
                    .eq("version", req.version())
                    .setSql("version = version + 1")) == 0) {
                throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
            }
            docLogService.add(BizType.MRP_CALC, id, "recalc", "就地重算，手改的数都按新算的");
            return new DocSaved(id, c.getNo(), req.version() + 1);
        }

        MrpCalc existing = nextDraft(c);
        if (existing != null) {
            throw new BizException("已经有一张重新算料的草稿 " + existing.getNo() + "，在那张上接着改");
        }
        // 订单后来结案、取消的不再算；都没了就不用重算，关掉这张
        List<MrpCalcMapper.OrderHead> all = calcMapper.selectOrderHeads(orderIds);
        List<MrpCalcMapper.OrderHead> heads = all.stream().filter(h -> MrpEngine.CALC_ORDER_STATUS.contains(h.status())).toList();
        if (heads.isEmpty()) {
            throw new BizException("这张算料单的订单都结案或取消了，不用重新算料，关闭它就行");
        }
        MrpEngine.Result r = engine.compute(heads, Set.of(id), id);
        MrpCalc n = new MrpCalc();
        n.setNo(serialNoGenerator.daily(BizType.MRP_CALC.getPrefix()));
        n.setPrevCalcId(id);
        n.setStatus(DRAFT);
        n.setNeedDate(r.needDate());
        n.setInputSnapshot(r.snapshot());
        calcMapper.insert(n);
        calcMapper.insertOrders(n.getId(), heads.stream().map(MrpCalcMapper.OrderHead::id).toList());
        insertRows(n.getId(), r.rows());
        String skipped = all.stream().filter(h -> !heads.contains(h)).map(MrpCalcMapper.OrderHead::no).collect(Collectors.joining("、"));
        docLogService.add(BizType.MRP_CALC, n.getId(), "recalc", "由 " + c.getNo() + " 重新算料"
                + (skipped.isEmpty() ? "" : "；订单 " + skipped + " 已结案或取消，不再算"));
        docLogService.add(BizType.MRP_CALC, id, "recalc", "另算了一张 " + n.getNo() + "，它确认时这张自动关闭");
        return new DocSaved(n.getId(), n.getNo(), 0);
    }

    // ---------- 改行、删行、备注 ----------

    @Override
    @Transactional
    public MrpCalcDetailVO updateItem(long id, long itemId, MrpItemForm f) {
        MrpCalc c = lock(id);
        FLOW.next("edit", c.getStatus(), c.getStatus());
        checkVersion(c, f.getVersion());
        MrpCalc next = nextDraft(c);
        if (next != null) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张有一张重新算料的草稿 " + next.getNo() + "，在新单上改");
        }
        MrpCalcItem item = item(c, itemId);
        boolean supplierChanged = !Objects.equals(item.getSupplierId(), f.getSupplierId());
        boolean stockChanged = item.getUseStockQty().compareTo(f.getUseStockQty()) != 0;
        boolean transitChanged = item.getUseTransitQty().compareTo(f.getUseTransitQty()) != 0;
        boolean suggestChanged = item.getSuggestQty().compareTo(f.getSuggestQty()) != 0;
        if (!supplierChanged && !stockChanged && !transitChanged && !suggestChanged) {
            return detail(id);
        }
        if (item.getBlock() != MrpBlock.PURCHASE) {
            throw new BizException("工厂自备的料不用我们买，这一行不能改");
        }
        if (c.getStatus() == CONFIRMED) {
            if (stockChanged || transitChanged || suggestChanged) {
                throw new BizException("已确认的算料单只能换供应商；要改数量就重新算料");
            }
            if (!convertibleRow(item)) {
                throw new BizException("这一行已经全转了采购，不用再换供应商");
            }
            if (f.getSupplierId() == null) {
                throw new BizException(400, "请选供应商");
            }
        }
        MaterialPurchaseInfo info = materialService.purchaseInfos(List.of(item.getMaterialSkuId())).get(item.getMaterialSkuId());
        String sku = info.skuCode();
        List<String> changes = new ArrayList<>();
        if (supplierChanged) {
            if (f.getSupplierId() != null) {
                supplierService.checkUsable(f.getSupplierId(), SupplierService.Usage.BULK);
            }
            Map<Long, SupplierRef> names = supplierService.refs(Set.of(Objects.requireNonNullElse(item.getSupplierId(), 0L),
                    Objects.requireNonNullElse(f.getSupplierId(), 0L)));
            changes.add("供应商 " + supplierName(names, item.getSupplierId()) + " → " + supplierName(names, f.getSupplierId()));
            item.setSupplierId(f.getSupplierId());
            MrpCalculator.reprice(item, info, engine.price(f.getSupplierId(), info));
        }
        if (stockChanged || transitChanged) {
            BigDecimal maxStock = item.getAvailStockQty().min(item.getNeedQty());
            if (f.getUseStockQty().compareTo(maxStock) > 0) {
                throw new BizException(400, "本次用库存最多 " + PurTexts.plain(maxStock));
            }
            BigDecimal maxTransit = item.getAvailTransitQty().min(item.getNeedQty().subtract(f.getUseStockQty())).max(BigDecimal.ZERO);
            if (f.getUseTransitQty().compareTo(maxTransit) > 0) {
                throw new BizException(400, "本次用在途最多 " + PurTexts.plain(maxTransit));
            }
            if (stockChanged) {
                changes.add("本次用库存 " + PurTexts.plain(item.getUseStockQty()) + " → " + PurTexts.plain(f.getUseStockQty()));
            }
            if (transitChanged) {
                changes.add("本次用在途 " + PurTexts.plain(item.getUseTransitQty()) + " → " + PurTexts.plain(f.getUseTransitQty()));
            }
            reallocate(c, item, Num.qty(f.getUseTransitQty()));
            item.setUseStockQty(Num.qty(f.getUseStockQty()));
            item.setUseTransitQty(Num.qty(f.getUseTransitQty()));
            MrpCalculator.settle(item, info, engine.price(item.getSupplierId(), info));
        } else if (suggestChanged) {
            if (f.getSuggestQty().compareTo(item.getConvertedQty()) < 0) {
                throw new BizException(400, "建议采购量不能小于已转采购 " + PurTexts.plain(item.getConvertedQty()));
            }
            changes.add("建议采购 " + PurTexts.plain(item.getSuggestQty()) + " → " + PurTexts.plain(f.getSuggestQty()));
            item.setSuggestQty(Num.qty(f.getSuggestQty()));
            item.setEstAmount(Num.amount(item.getSuggestQty(), item.getRefPrice()));
        }
        itemMapper.update(item, Updates.replaceById(item));
        bumpVersion(c);
        docLogService.add(BizType.MRP_CALC, id, "update-item", "第 " + item.getSeq() + " 行 " + sku + "：" + String.join("，", changes));
        return detail(id);
    }

    private static String supplierName(Map<Long, SupplierRef> refs, Long id) {
        SupplierRef s = id == null ? null : refs.get(id);
        return s == null ? "（没选）" : s.shortName();
    }

    private MrpCalcItem item(MrpCalc c, long itemId) {
        MrpCalcItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getCalcId().equals(c.getId())) {
            throw new BizException(404, "这一行不在这张算料单上了，请刷新");
        }
        return item;
    }

    /**
     * 草稿里改了本次用在途：按现在的在途重新分到采购行上（预计到货从早到晚，只用赶得上的）。
     * 现在能用的比要的少（别人刚确认用掉了），1000 让重新算料
     */
    private void reallocate(MrpCalc c, MrpCalcItem item, BigDecimal want) {
        Set<Long> excluded = excludedOf(c);
        SupplyBook book = supplyLoader.load(List.of(item.getMaterialSkuId()), excluded);
        List<MrpCalcTransit> rows = new ArrayList<>();
        BigDecimal left = want;
        BigDecimal free = BigDecimal.ZERO;
        for (SupplyBook.FreeLine f : book.freeLines(item.getMaterialSkuId(), excluded, c.getPrevCalcId())) {
            if (!MrpCalculator.catchesUp(f.line().eta(), c.getNeedDate())) {
                continue;
            }
            free = free.add(f.freeQty());
            BigDecimal take = f.freeQty().min(left);
            if (take.signum() > 0) {
                rows.add(transit(item.getId(), f.line().id(), take, f.line().eta()));
                left = left.subtract(take);
            }
        }
        if (left.signum() > 0) {
            throw new BizException("能用的在途现在只有 " + PurTexts.plain(free) + "，" + STALE_SUPPLY);
        }
        transitMapper.deleteByItem(item.getId());
        rows.forEach(transitMapper::insert);
    }

    @Override
    @Transactional
    public MrpCalcDetailVO removeItem(long id, long itemId, int version) {
        MrpCalc c = lock(id);
        FLOW.next("removeItem", c.getStatus());
        checkVersion(c, version);
        MrpCalcItem item = item(c, itemId);
        if (item.getConvertedQty().signum() > 0) {
            throw new BizException("这一行已经转了采购，不能删");
        }
        String sku = materialService.skuBriefs(List.of(item.getMaterialSkuId())).values().stream()
                .map(MaterialSkuBrief::getSkuCode).findFirst().orElse("");
        transitMapper.deleteByItem(itemId);
        itemMapper.deleteById(itemId);
        bumpVersion(c);
        docLogService.add(BizType.MRP_CALC, id, "remove-item", "第 " + item.getSeq() + " 行 " + sku);
        return detail(id);
    }

    @Override
    @Transactional
    public void updateRemark(long id, RemarkReq req) {
        MrpCalc c = lock(id);
        if (c.getStatus() == CLOSED) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "算料单已关闭，不能改备注");
        }
        checkVersion(c, req.getVersion());
        if (calcMapper.update(new MrpCalc(), new LambdaUpdateWrapper<MrpCalc>()
                .set(MrpCalc::getRemark, PurTexts.blankToNull(req.getRemark()))
                .setSql("version = version + 1")
                .eq(MrpCalc::getId, id)
                .eq(MrpCalc::getVersion, req.getVersion())) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
    }

    // ---------- 确认、关闭、删除 ----------

    @Override
    @Transactional
    public void confirm(long id, DocActionReq req) {
        MrpCalc c = lockWithPrev(id);
        FLOW.next("confirm", c.getStatus());
        checkVersion(c, req.version());
        doConfirm(c);
    }

    /**
     * 确认（§3.2.7）：按 SKU 加咨询锁排队，再按 ID 排序排他锁住在途及待改挂行的采购头，避免共享锁升级和头/行反序。
     * 用最新的数再算每行的可用库存、可用在途，
     * 对不上 1000（data 是哪几行怎么变了）。过了改成已确认；是重新算料出来的，关上一张、把上一张生成的采购行转过来。
     * 调之前已经锁住这张和上一张
     */
    private void doConfirm(MrpCalc c) {
        List<MrpCalcItem> items = itemMapper.selectByCalc(c.getId());
        List<MrpCalcItem> buy = items.stream().filter(i -> i.getBlock() == MrpBlock.PURCHASE).toList();
        Set<Long> skuIds = buy.stream().map(MrpCalcItem::getMaterialSkuId).collect(Collectors.toCollection(TreeSet::new));
        if (!skuIds.isEmpty()) {
            calcMapper.lockSkus(skuIds);
        }
        List<MrpCalcTransit> uses = transitMapper.selectByCalc(c.getId());
        Set<Long> poIds = new TreeSet<>();
        if (!uses.isEmpty()) {
            poItemMapper.selectLineRefs(uses.stream().map(MrpCalcTransit::getPurchaseItemId).collect(Collectors.toSet()))
                    .stream().map(PurchaseOrderItemMapper.LineRef::poId).forEach(poIds::add);
        }
        if (c.getPrevCalcId() != null) {
            List<Long> moving = itemMapper.selectByCalc(c.getPrevCalcId()).stream()
                    .filter(i -> i.getBlock() == MrpBlock.PURCHASE && skuIds.contains(i.getMaterialSkuId()))
                    .map(MrpCalcItem::getId).toList();
            if (!moving.isEmpty()) {
                poIds.addAll(poItemMapper.selectPoIdsOfCalcItems(moving));
            }
        }
        if (!poIds.isEmpty()) {
            poMapper.lockForUpdate(poIds);
        }
        Set<Long> excluded = excludedOf(c);
        SupplyBook book = supplyLoader.load(skuIds, excluded);
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(skuIds);
        List<String> changed = staleRows(c, buy, uses, book, excluded, infos);
        if (!changed.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, STALE_SUPPLY, changed);
        }
        StatusCas.update(calcMapper, new MrpCalc(), StatusCas.of(MrpCalc::getId, c.getId(), MrpCalc::getStatus, DRAFT, CONFIRMED)
                .set(MrpCalc::getAuditBy, LoginUser.currentUserId())
                .set(MrpCalc::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(MrpCalc::getVersion, c.getVersion()));
        c.setVersion(c.getVersion() + 1);
        c.setStatus(CONFIRMED);
        String remark = c.getPrevCalcId() == null ? null : closeForRecalc(c, items);
        docLogService.add(BizType.MRP_CALC, c.getId(), "confirm", DRAFT, CONFIRMED, remark);
    }

    /**
     * 存着的可用量和现在的对不上的行：可用库存、可用在途（赶得上的）任一不同，或者用的哪条在途现在不够了。
     * 都对得上是空的
     */
    static List<String> staleRows(MrpCalc c, List<MrpCalcItem> buy, List<MrpCalcTransit> uses, SupplyBook book, Set<Long> excluded,
                                  Map<Long, MaterialPurchaseInfo> infos) {
        Map<Long, List<MrpCalcTransit>> usesByItem = uses.stream().collect(Collectors.groupingBy(MrpCalcTransit::getCalcItemId));
        List<String> out = new ArrayList<>();
        for (MrpCalcItem i : buy) {
            long sku = i.getMaterialSkuId();
            String code = infos.containsKey(sku) ? infos.get(sku).skuCode() : String.valueOf(sku);
            BigDecimal stock = book.availStock(sku, excluded, c.getPrevCalcId());
            Map<Long, SupplyBook.FreeLine> free = new HashMap<>();
            BigDecimal transit = BigDecimal.ZERO;
            for (SupplyBook.FreeLine f : book.freeLines(sku, excluded, c.getPrevCalcId())) {
                if (MrpCalculator.catchesUp(f.line().eta(), c.getNeedDate())) {
                    free.put(f.line().id(), f);
                    transit = transit.add(f.freeQty());
                }
            }
            if (stock.compareTo(i.getAvailStockQty()) != 0 || transit.compareTo(i.getAvailTransitQty()) != 0) {
                out.add(code + "：可用库存 " + PurTexts.plain(i.getAvailStockQty()) + " → " + PurTexts.plain(stock)
                        + "，可用在途 " + PurTexts.plain(i.getAvailTransitQty()) + " → " + PurTexts.plain(transit));
                continue;
            }
            for (MrpCalcTransit u : usesByItem.getOrDefault(i.getId(), List.of())) {
                SupplyBook.FreeLine f = free.get(u.getPurchaseItemId());
                if (f == null || f.freeQty().compareTo(u.getQty()) < 0) {
                    out.add(code + "：用的在途 " + (f == null ? "" : f.line().poNo() + " ") + "现在不够 " + PurTexts.plain(u.getQty()) + " 了");
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 重新算料的新单确认了：上一张关闭（RECALC），上一张每一行生成的采购行改指这张同一个 SKU 的行，已转采购跟着算；
     * 这张没有了的 SKU，它的采购行留在上一张（不再占着，谁都能用）。涉及的采购单版本号 +1。返回写进确认记录的话
     */
    private String closeForRecalc(MrpCalc c, List<MrpCalcItem> items) {
        MrpCalc prev = calcMapper.selectById(c.getPrevCalcId());
        StatusCas.update(calcMapper, new MrpCalc(), StatusCas.of(MrpCalc::getId, prev.getId(), MrpCalc::getStatus, CONFIRMED, CLOSED)
                .set(MrpCalc::getCloseReason, MrpCloseReason.RECALC)
                .set(MrpCalc::getCloseTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1"));
        Map<Long, MrpCalcItem> mine = items.stream().filter(i -> i.getBlock() == MrpBlock.PURCHASE)
                .collect(Collectors.toMap(MrpCalcItem::getMaterialSkuId, i -> i));
        Set<Long> touched = new HashSet<>();
        Set<Long> poIds = new TreeSet<>();
        List<Long> dropped = new ArrayList<>();
        for (MrpCalcItem pi : itemMapper.selectByCalc(prev.getId())) {
            if (pi.getBlock() != MrpBlock.PURCHASE) {
                continue;
            }
            List<Long> pos = poItemMapper.selectPoIdsOfCalcItem(pi.getId());
            if (pos.isEmpty()) {
                continue;
            }
            MrpCalcItem ni = mine.get(pi.getMaterialSkuId());
            if (ni == null) {
                dropped.add(pi.getMaterialSkuId());
                continue;
            }
            poItemMapper.moveCalcItem(pi.getId(), ni.getId());
            poIds.addAll(pos);
            touched.add(pi.getId());
            touched.add(ni.getId());
        }
        if (!touched.isEmpty()) {
            itemMapper.syncConverted(touched);
        }
        if (!poIds.isEmpty()) {
            poMapper.bumpVersions(poIds);
        }
        docLogService.add(BizType.MRP_CALC, prev.getId(), "close", CONFIRMED, CLOSED, "重新算料的新单 " + c.getNo() + " 确认了");
        StringBuilder text = new StringBuilder("上一张 " + prev.getNo() + " 自动关闭");
        if (!poIds.isEmpty()) {
            text.append("；它生成的 ").append(poIds.size()).append(" 张采购单转到这张");
        }
        if (!dropped.isEmpty()) {
            String codes = materialService.skuBriefs(dropped).values().stream().map(MaterialSkuBrief::getSkuCode)
                    .collect(Collectors.joining("、"));
            text.append("；").append(codes).append(" 这次不要了，已经买的留在上一张，不再占着");
        }
        return text.toString();
    }

    @Override
    @Transactional
    public void close(long id, DocActionReq req) {
        MrpCalc c = lock(id);
        FLOW.next("close", c.getStatus());
        checkVersion(c, req.version());
        MrpCalc next = nextDraft(c);
        if (next != null) {
            throw new BizException("有一张重新算料的草稿 " + next.getNo() + "：先确认它（这张自动关闭）或者删掉它");
        }
        StatusCas.update(calcMapper, new MrpCalc(), StatusCas.of(MrpCalc::getId, id, MrpCalc::getStatus, CONFIRMED, CLOSED)
                .set(MrpCalc::getCloseReason, MrpCloseReason.MANUAL)
                .set(MrpCalc::getCloseBy, LoginUser.currentUserId())
                .set(MrpCalc::getCloseTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(MrpCalc::getVersion, req.version()));
        docLogService.add(BizType.MRP_CALC, id, "close", CONFIRMED, CLOSED, PurTexts.blankToNull(req.reason()));
    }

    @Override
    @Transactional
    public void delete(long id) {
        MrpCalc c = lock(id);
        FLOW.next("remove", c.getStatus());
        transitMapper.deleteByCalc(id);
        itemMapper.deleteByCalc(id);
        calcMapper.deleteOrders(id);
        attachmentService.deleteByDoc(BizType.MRP_CALC, id);
        docLogService.deleteByDoc(BizType.MRP_CALC, id);
        docNoticeService.deleteByDoc(BizType.MRP_CALC, id);
        calcMapper.deleteById(id);
    }

    // ---------- 备料比 ----------

    @Override
    public List<ReservePctVO> reservePcts() {
        return dictDataService.listWithText("material_type").stream()
                .filter(d -> Integer.valueOf(1).equals(d.getStatus()))
                .map(d -> new ReservePctVO(d.getValue(), d.getLabel(), d.getNumValue() == null ? BigDecimal.ZERO : d.getNumValue()))
                .toList();
    }

    @Override
    @Transactional
    public void saveReservePcts(List<ReservePctReq> list) {
        Map<String, BigDecimal> nums = new LinkedHashMap<>();
        list.forEach(r -> nums.put(r.getMaterialType(), r.getPct()));
        dictDataService.updateNumValues("material_type", nums);
    }

    // ---------- 生成采购单（PurchaseOrderServiceImpl 调） ----------

    @Override
    public List<MrpCalcItem> lockForConvert(long id, List<Long> itemIds, int version) {
        MrpCalc c = lockWithPrev(id);
        if (c.getStatus() == CLOSED) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "算料单已关闭，不能再生成采购单");
        }
        checkVersion(c, version);
        MrpCalc next = nextDraft(c);
        if (next != null) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张有一张重新算料的草稿 " + next.getNo() + "，在新单上生成采购单");
        }
        if (c.getStatus() == DRAFT) {
            FLOW.next("confirm", c.getStatus());
            doConfirm(c);
        }
        Set<Long> wanted = new HashSet<>(itemIds);
        List<MrpCalcItem> items = itemMapper.selectByCalc(id).stream().filter(i -> wanted.contains(i.getId())).toList();
        if (items.size() != wanted.size()) {
            throw new BizException(404, "勾的行有的不在这张算料单上了，请刷新");
        }
        List<Integer> done = items.stream().filter(i -> !convertibleRow(i)).map(MrpCalcItem::getSeq).toList();
        if (!done.isEmpty()) {
            throw new BizException(PurTexts.rows(done) + "不用再买了（已经全转了采购，或者是工厂自备的）");
        }
        List<Integer> noSupplier = items.stream().filter(i -> i.getSupplierId() == null).map(MrpCalcItem::getSeq).toList();
        if (!noSupplier.isEmpty()) {
            throw new BizException(PurTexts.rows(noSupplier) + "还没选供应商");
        }
        return items;
    }

    @Override
    public void afterConvert(MrpCalc calc, List<MrpCalcItem> items, String text) {
        itemMapper.syncConverted(items.stream().map(MrpCalcItem::getId).toList());
        calcMapper.update(new MrpCalc(), new LambdaUpdateWrapper<MrpCalc>()
                .setSql("version = version + 1")
                .eq(MrpCalc::getId, calc.getId()));
        docLogService.add(BizType.MRP_CALC, calc.getId(), "to-purchase", text);
    }

    @Override
    public List<Long> lockCalcsOfItems(Collection<Long> calcItemIds) {
        List<Long> ids = calcItemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> calcIds = poItemMapper.selectCalcIdsOfItems(ids);
        return calcIds.isEmpty() ? List.of() : calcMapper.lockByIds(calcIds);
    }

    @Override
    public void syncConverted(Collection<Long> calcItemIds) {
        List<Long> ids = calcItemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            itemMapper.syncConverted(ids);
        }
    }

    // ---------- 齐料 ----------

    @Override
    public Set<Long> calcPeers(Collection<Long> orderIds) {
        Set<Long> out = new HashSet<>(orderIds);
        if (orderIds.isEmpty()) {
            return out;
        }
        Set<Long> calcIds = calcMapper.selectOrderCalcs(orderIds).stream()
                .filter(c -> CONFIRMED.name().equals(c.status())).map(MrpCalcMapper.OrderCalc::calcId).collect(Collectors.toSet());
        if (!calcIds.isEmpty()) {
            calcMapper.selectCalcOrders(calcIds).forEach(o -> out.add(o.orderId()));
        }
        return out;
    }

    @Override
    @Transactional
    public void syncIssued(Map<Long, Map<Long, BigDecimal>> issuedByOrder, Set<Long> doneOrders) {
        Set<Long> calcIds = issuedByOrder.isEmpty() ? Set.of() : calcMapper.selectOrderCalcs(issuedByOrder.keySet()).stream()
                .filter(c -> CONFIRMED.name().equals(c.status())).map(MrpCalcMapper.OrderCalc::calcId).collect(Collectors.toSet());
        if (calcIds.isEmpty()) {
            return;
        }
        calcMapper.lockByIds(calcIds);
        Map<Long, List<Long>> ordersOf = new HashMap<>();
        calcMapper.selectCalcOrders(calcIds).forEach(o -> ordersOf.computeIfAbsent(o.calcId(), k -> new ArrayList<>()).add(o.orderId()));
        List<MrpCalc> calcs = calcMapper.selectByIds(calcIds).stream().filter(c -> c.getStatus() == CONFIRMED).toList();
        Set<Long> live = calcs.stream().map(MrpCalc::getId).collect(Collectors.toSet());
        for (MrpCalcItem i : itemMapper.selectByCalcs(live)) {
            if (i.getBlock() != MrpBlock.PURCHASE) {
                continue;
            }
            BigDecimal issued = BigDecimal.ZERO;
            for (Long orderId : ordersOf.getOrDefault(i.getCalcId(), List.of())) {
                issued = issued.add(issuedByOrder.getOrDefault(orderId, Map.of()).getOrDefault(i.getMaterialSkuId(), BigDecimal.ZERO));
            }
            issued = Num.qty(issued.max(BigDecimal.ZERO));
            if (issued.compareTo(i.getIssuedQty()) != 0) {
                itemMapper.updateIssued(i.getId(), issued);
            }
        }
        for (MrpCalc c : calcs) {
            List<Long> orders = ordersOf.getOrDefault(c.getId(), List.of());
            if (orders.isEmpty() || !doneOrders.containsAll(orders) || nextDraft(c) != null) {
                continue;
            }
            StatusCas.update(calcMapper, new MrpCalc(), StatusCas.of(MrpCalc::getId, c.getId(), MrpCalc::getStatus, CONFIRMED, CLOSED)
                    .set(MrpCalc::getCloseReason, MrpCloseReason.ISSUED)
                    .set(MrpCalc::getCloseTime, OffsetDateTime.now(clock))
                    .setSql("version = version + 1"));
            docLogService.add(BizType.MRP_CALC, c.getId(), "close", CONFIRMED, CLOSED, "订单的生产单都领齐了料，自动关闭");
        }
    }

    @Override
    public List<OrderKittingVO> kitting(List<Long> orderIds) {
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 500) {
            throw new BizException(400, "一次最多查 500 张订单");
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MrpCalcMapper.OrderCalc>> byOrder = calcMapper.selectOrderCalcs(ids).stream()
                .filter(c -> MrpStatus.CONFIRMED.name().equals(c.status())
                        || MrpStatus.CLOSED.name().equals(c.status()) && MrpCloseReason.ISSUED.name().equals(c.closeReason()))
                .collect(Collectors.groupingBy(MrpCalcMapper.OrderCalc::orderId));
        Set<Long> confirmed = byOrder.values().stream().flatMap(List::stream)
                .filter(c -> MrpStatus.CONFIRMED.name().equals(c.status())).map(MrpCalcMapper.OrderCalc::calcId)
                .collect(Collectors.toSet());
        Set<Long> issued = byOrder.values().stream().flatMap(List::stream)
                .filter(c -> !MrpStatus.CONFIRMED.name().equals(c.status())).map(MrpCalcMapper.OrderCalc::calcId)
                .collect(Collectors.toSet());
        Set<Long> all = new HashSet<>(confirmed);
        all.addAll(issued);
        List<MrpCalcItem> items = all.isEmpty() ? List.of()
                : itemMapper.selectByCalcs(all).stream().filter(i -> i.getBlock() == MrpBlock.PURCHASE).toList();
        List<MrpCalcItem> live = items.stream().filter(i -> confirmed.contains(i.getCalcId())).toList();
        Map<Long, List<MrpCalcTransit>> uses = new HashMap<>();
        for (Long calcId : confirmed) {
            transitMapper.selectByCalc(calcId).forEach(u -> uses.computeIfAbsent(u.getCalcItemId(), k -> new ArrayList<>()).add(u));
        }
        Progress p = progress(live, uses.values().stream().flatMap(List::stream).toList(), confirmed);
        Map<Long, int[]> counts = new HashMap<>();   // 算料单 → [齐了的行, 全部行]
        for (MrpCalcItem i : items) {
            int[] n = counts.computeIfAbsent(i.getCalcId(), k -> new int[2]);
            n[1]++;
            if (issued.contains(i.getCalcId()) || p.shortOf(i, uses.getOrDefault(i.getId(), List.of())).signum() == 0) {
                n[0]++;
            }
        }
        List<OrderKittingVO> out = new ArrayList<>();
        for (Long orderId : ids) {
            List<Long> calcIds = byOrder.getOrDefault(orderId, List.of()).stream().map(MrpCalcMapper.OrderCalc::calcId)
                    .sorted(Comparator.reverseOrder()).toList();
            if (calcIds.isEmpty()) {
                out.add(new OrderKittingVO(orderId, "NONE", 0, 0, "未算料", List.of()));
                continue;
            }
            int ready = 0;
            int total = 0;
            for (Long calcId : calcIds) {
                int[] n = counts.getOrDefault(calcId, new int[2]);
                ready += n[0];
                total += n[1];
            }
            String status = ready == total ? "DONE" : ready == 0 ? "NOT" : "PARTIAL";
            String label = switch (status) {
                case "DONE" -> "齐料";
                case "NOT" -> "未到料";
                default -> "部分到料 " + ready + "/" + total;
            };
            out.add(new OrderKittingVO(orderId, status, ready, total, label, calcIds));
        }
        return out;
    }
}
