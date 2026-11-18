package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.production.dto.ScheduleOverRow;
import com.lingmao.scm.module.production.dto.SchedulePrefillVO;
import com.lingmao.scm.module.production.dto.ScheduleQuoteVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.dto.ScheduleSkuVO;
import com.lingmao.scm.module.production.dto.ScheduleStyleVO;
import com.lingmao.scm.module.production.entity.MaterialIssueStatus;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.service.ScheduleService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.service.TechPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    /** 生产单默认交期比承诺交期早几天（PRD §6.6.1，和 03 的 NeedDates 一样） */
    static final int DUE_BEFORE_COMMIT_DAYS = 10;

    private static final Set<DocStatus> SCHEDULABLE = Set.of(APPROVED, RUNNING);

    private final ProductionOrderMapper prdMapper;
    private final ProductionOrderItemMapper itemMapper;
    private final ProductionSupport support;
    private final OrderService orderService;
    private final StyleService styleService;
    private final TechPackService techPackService;
    private final SupplierService supplierService;
    private final PriceService priceService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocLogService docLogService;
    private final SerialNoGenerator serialNoGenerator;

    private static void checkOrder(OrderForProduction o) {
        if (!SCHEDULABLE.contains(o.status())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "订单 " + o.no() + " 现在是「" + o.status().getLabel()
                    + "」，已审核、生产中的订单才能排单");
        }
    }

    @Override
    public SchedulePrefillVO prefill(long orderId) {
        OrderForProduction o = orderService.getForProduction(orderId);
        checkOrder(o);
        Map<Long, List<OrderForProduction.Line>> byStyle = new LinkedHashMap<>();
        o.items().stream().filter(l -> l.qty() > 0).forEach(l -> byStyle.computeIfAbsent(l.styleId(), k -> new ArrayList<>()).add(l));
        byStyle.values().removeIf(ls -> ls.stream().noneMatch(l -> l.scheduledQty() < l.qty()));

        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(byStyle.keySet());
        Set<Long> packIds = byStyle.values().stream().map(ls -> ls.getFirst().techPackId()).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PackBrief> packs = techPackService.briefs(packIds);
        Map<Long, BigDecimal> labor = techPackService.laborTotals(packIds);
        Map<Long, SupplierRef> factories = supplierService.refs(infos.values().stream().map(StyleOrderInfo::defaultFactoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        List<ScheduleStyleVO> styles = new ArrayList<>();
        byStyle.forEach((styleId, lines) -> {
            StyleOrderInfo info = infos.get(styleId);
            Long packId = lines.getFirst().techPackId();
            PackBrief pack = packId == null ? null : packs.get(packId);
            Map<Long, StyleOrderInfo.Sku> skus = info.skus().stream().collect(Collectors.toMap(StyleOrderInfo.Sku::id, Function.identity()));
            Set<Long> colorIds = new LinkedHashSet<>();
            List<ScheduleSkuVO> skuVOs = new ArrayList<>();
            for (OrderForProduction.Line l : lines) {
                StyleOrderInfo.Sku sku = skus.get(l.styleSkuId());
                colorIds.add(sku.colorId());
                skuVOs.add(new ScheduleSkuVO(l.id(), l.styleSkuId(), sku.colorId(), sku.sizeCode(), l.qty(), l.scheduledQty(),
                        Math.max(l.qty() - l.scheduledQty(), 0), Math.max(ProductionRules.maxPieces(l.qty()) - l.scheduledQty(), 0)));
            }
            List<ColorOption> colors = info.colors().stream().filter(c -> colorIds.contains(c.id()))
                    .map(c -> new ColorOption(c.id(), c.code(), c.name(), c.hex())).toList();
            SupplierRef factory = info.defaultFactoryId() == null ? null : factories.get(info.defaultFactoryId());
            styles.add(new ScheduleStyleVO(styleId, info.code(), info.name(), info.image(), packId == null ? 0 : packId,
                    pack == null ? 0 : pack.version(), packId == null ? null : labor.get(packId), info.defaultFactoryId(),
                    factory == null ? null : factory.shortName(), info.sizes(), colors, skuVOs));
        });
        LocalDate base = o.commitDate() != null ? o.commitDate() : o.requiredDate();
        return new SchedulePrefillVO(o.id(), o.no(), o.commitDate(), o.requiredDate(), base.minusDays(DUE_BEFORE_COMMIT_DAYS),
                o.merchId(), userService.nicknames(List.of(o.merchId())).get(o.merchId()), styles);
    }

    @Override
    public ScheduleQuoteVO quote(long orderId, long styleId, long factoryId) {
        orderService.getForProduction(orderId);
        SupplierRef f = supplierService.getRef(factoryId);
        String problem = ProductionSupport.factoryProblem(f, supplierService.categories(List.of(factoryId))
                .getOrDefault(factoryId, List.of()), "生产单");
        LocalDate today = support.today();
        List<ScheduleQuoteVO.ModePrice> prices = new ArrayList<>();
        for (DictOption mode : modes()) {
            PriceRef p = priceService.currentProcess(factoryId, styleId, mode.value(), today).orElse(null);
            prices.add(new ScheduleQuoteVO.ModePrice(mode.value(), p == null ? null : p.price(), p == null ? null : p.id(),
                    p == null ? null : p.effectiveDate()));
        }
        String defaultMode = prices.stream().filter(p -> p.price() != null).map(ScheduleQuoteVO.ModePrice::processMode).findFirst()
                .orElse(prices.isEmpty() ? null : prices.getFirst().processMode());
        return new ScheduleQuoteVO(f.id(), f.shortName(), problem == null, problem, f.settleMethod(), f.taxPct(), prices, defaultMode);
    }

    /** 字典 process_mode 启用的，按顺序 */
    private List<DictOption> modes() {
        return dictDataService.listByType("process_mode").stream().filter(DictOption::enabled).toList();
    }

    @Override
    @Transactional
    public List<DocRef> schedule(ScheduleReq req) {
        OrderForProduction o = orderService.lockForProduction(req.getOrderId());
        checkOrder(o);
        Map<Long, OrderForProduction.Line> lines = o.items().stream()
                .collect(Collectors.toMap(OrderForProduction.Line::id, Function.identity()));
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(req.getBlocks().stream().map(ScheduleReq.Block::getStyleId)
                .collect(Collectors.toSet()));

        // 一块一块理出来：0 件的行、整块是 0 的不要；行要是这个款在这张订单上的
        List<Block> blocks = new ArrayList<>();
        Map<Long, Integer> thisQty = new LinkedHashMap<>();
        for (int n = 0; n < req.getBlocks().size(); n++) {
            ScheduleReq.Block b = req.getBlocks().get(n);
            List<ScheduleReq.Item> items = b.getItems().stream().filter(i -> i.getQty() > 0).toList();
            if (items.isEmpty()) {
                continue;
            }
            Set<Long> seen = new HashSet<>();
            for (ScheduleReq.Item i : items) {
                OrderForProduction.Line l = lines.get(i.getOrderItemId());
                if (l == null || l.styleId() != b.getStyleId() || !seen.add(i.getOrderItemId())) {
                    throw new BizException(400, "第 " + (n + 1) + " 块的明细和订单对不上，请刷新后再排");
                }
                long requested = thisQty.getOrDefault(l.id(), 0).longValue() + i.getQty();
                thisQty.put(l.id(), NumericLimits.pieces(requested, "第 " + (n + 1) + " 块同一 SKU 累计排单："));
            }
            if (infos.get(b.getStyleId()) == null) {
                throw new BizException(400, "第 " + (n + 1) + " 块的款式不存在了，请刷新后再排");
            }
            blocks.add(new Block(n + 1, b, items));
        }
        if (blocks.isEmpty()) {
            throw new BizException(400, "没有要排的件数");
        }
        checkOver(thisQty, lines, infos);
        NumericLimits.pieces(o.items().stream().mapToLong(OrderForProduction.Line::scheduledQty).sum()
                + thisQty.values().stream().mapToLong(Integer::longValue).sum(), "需求订单累计已排：");

        Map<Long, SupplierRef> factories = supplierService.refs(blocks.stream().map(b -> b.req().getFactoryId()).collect(Collectors.toSet()));
        Map<Long, List<String>> categories = supplierService.categories(factories.keySet());
        Set<String> modes = modes().stream().map(DictOption::value).collect(Collectors.toSet());
        LocalDate today = support.today();
        List<String> problems = new ArrayList<>();
        List<ProductionOrder> heads = new ArrayList<>();
        for (Block blk : blocks) {
            ScheduleReq.Block b = blk.req();
            StyleOrderInfo style = infos.get(b.getStyleId());
            SupplierRef f = factories.get(b.getFactoryId());
            String where = "第 " + blk.seq() + " 块（" + style.code() + (f == null ? "" : " / " + f.shortName()) + "）：";
            if (f == null) {
                throw new BizException(404, where + "加工厂不存在");
            }
            String mode = b.getProcessMode().strip();
            ProductionKind kind = ProductionKind.of(mode);
            if (!modes.contains(mode)) {
                problems.add(where + "加工方式「" + mode + "」不在字典里或已停用");
            }
            String fp = ProductionSupport.factoryProblem(f, categories.getOrDefault(f.id(), List.of()), kind.getLabel());
            if (fp != null) {
                problems.add(where + fp);
            }
            Long packId = lines.get(blk.items().getFirst().getOrderItemId()).techPackId();
            if (packId == null) {
                problems.add(where + "订单还没锁定这个款的生产 BOM");
            }
            BigDecimal price = NumericLimits.price(b.getUnitPrice(), where);
            PriceRef archive = priceService.currentProcess(f.id(), b.getStyleId(), mode, today).orElse(null);
            boolean changed = archive != null && price != null && price.compareTo(archive.price()) != 0;
            String reason = changed ? ProdTexts.blankToNull(b.getPriceChangeReason()) : null;
            if (price == null || price.signum() <= 0) {
                problems.add(where + kind.getPriceLabel() + "没填");
            } else if (changed && reason == null) {
                problems.add(where + "单价和档案现价 " + ProdTexts.money(archive.price()) + " 不一样，要写改价原因");
            }
            if (!userService.isEnabled(b.getMerchId())) {
                problems.add(where + "生产跟单的账号不存在或已停用");
            }
            String settle = ProdTexts.blankToNull(b.getSettleMethod()) != null ? b.getSettleMethod().strip() : f.settleMethod();
            if (settle == null) {
                problems.add(where + "结算方式没填，加工厂档案里也没有");
            }

            ProductionOrder p = new ProductionOrder();
            p.setCompanyId(o.companyId());
            p.setKind(kind);
            p.setFactoryId(f.id());
            p.setProcessMode(mode);
            p.setOrderId(o.id());
            p.setStyleId(b.getStyleId());
            p.setTechPackId(packId);
            p.setOrderDate(today);
            p.setDueDate(b.getDueDate());
            p.setUnitPrice(price);
            p.setPriceSourceId(archive != null && !changed ? archive.id() : null);
            p.setPriceChangeReason(reason);
            p.setTaxPct(f.taxPct() != null ? f.taxPct() : BigDecimal.ZERO);
            p.setTaxIncluded(true);
            p.setMerchId(b.getMerchId());
            p.setSettleMethod(settle);
            p.setTerms(ProdTexts.blankToNull(b.getTerms()));
            p.setRemark(ProdTexts.blankToNull(b.getRemark()));
            p.setStatus(DRAFT);
            int total = NumericLimits.pieces(blk.items().stream().mapToLong(ScheduleReq.Item::getQty).sum(), where);
            p.setTotalQty(total);
            p.setTotalAmount(NumericLimits.amount(price == null ? null : Num.amount(BigDecimal.valueOf(total), price), where));
            heads.add(p);
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, "排不了：有 " + problems.size() + " 处要先处理", problems);
        }

        List<DocRef> out = new ArrayList<>();
        List<String> created = new ArrayList<>();
        Map<Long, String> names = userService.nicknames(heads.stream().map(ProductionOrder::getMerchId).collect(Collectors.toSet()));
        for (int n = 0; n < blocks.size(); n++) {
            ProductionOrder p = heads.get(n);
            Block blk = blocks.get(n);
            p.setNo(serialNoGenerator.daily(BizType.PRODUCTION_ORDER.prefix(p.getKind().name())));
            prdMapper.insert(p);
            List<ProductionOrderItem> items = new ArrayList<>();
            for (int i = 0; i < blk.items().size(); i++) {
                ScheduleReq.Item r = blk.items().get(i);
                ProductionOrderItem item = new ProductionOrderItem();
                item.setPrdOrderId(p.getId());
                item.setSeq(i + 1);
                item.setOrderItemId(r.getOrderItemId());
                item.setStyleSkuId(lines.get(r.getOrderItemId()).styleSkuId());
                item.setQty(r.getQty());
                items.add(item);
            }
            itemMapper.insert(items);
            MaterialIssueStatus issue = support.initialIssueStatus(p.getId());
            if (issue != null) {
                prdMapper.updateIssueStatus(p.getId(), issue.name());
            }
            docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), "create", "由需求订单 " + o.no() + " 排单生成");
            created.add(p.getNo() + "（" + p.getTotalQty() + " 件）");
            out.add(DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), DRAFT, ProductionRules.STATUS, p.getMerchId())
                    .withKind(p.getKind().name()).withOwnerName(names.get(p.getMerchId())));
        }
        orderService.addScheduled(thisQty);
        docLogService.add(BizType.ORDER, o.id(), "schedule", "生成 " + String.join("、", created));
        return out;
    }

    /** 理好的一块：第几块（报错用）、请求、件数 > 0 的行 */
    private record Block(int seq, ScheduleReq.Block req, List<ScheduleReq.Item> items) {
    }

    /** 超排（PRD §6.5.5）：同一个 SKU 已排产 + 这次 ≤ ⌊订单数 × 1.03⌋，超了 1004 */
    private static void checkOver(Map<Long, Integer> thisQty, Map<Long, OrderForProduction.Line> lines, Map<Long, StyleOrderInfo> infos) {
        List<ScheduleOverRow> over = new ArrayList<>();
        thisQty.forEach((lineId, qty) -> {
            OrderForProduction.Line l = lines.get(lineId);
            int next = NumericLimits.pieces((long) l.scheduledQty() + qty, "订单行 " + lineId + " 累计已排：");
            int max = ProductionRules.maxPieces(l.qty());
            if (next > max) {
                String sku = infos.get(l.styleId()).skus().stream().filter(s -> s.id() == l.styleSkuId())
                        .map(StyleOrderInfo.Sku::code).findFirst().orElse(String.valueOf(l.styleSkuId()));
                int left = Math.max(max - l.scheduledQty(), 0);
                over.add(new ScheduleOverRow(sku, l.qty(), l.scheduledQty(), qty, max,
                        left == 0 ? "已经排满了" : "最多还能排 " + left + " 件"));
            }
        });
        if (!over.isEmpty()) {
            throw new BizException(ErrorCodes.OVER_LIMIT, "有 " + over.size() + " 个 SKU 排超了：每个 SKU 最多排到订单数的 103%", over);
        }
    }
}
