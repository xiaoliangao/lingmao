package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.purchase.entity.MrpBlock;
import com.lingmao.scm.module.purchase.entity.MrpCalcItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 算料（PRD §6.6.1 的公式）：订单 × 生产 BOM 展开出来的需求行，按物料 SKU 汇总成结果行，再按 SupplyBook 扣库存、扣赶得上的在途。
 * 只算，不碰库：输入都由 MrpServiceImpl 取好，单测直接喂数。
 * <pre>
 * 毛需求 = Σ 件数 × 单件用量 ×（1 + 损耗率）      需求 = 毛需求 ×（1 + 备料比）
 * 本次使用库存 = min（可用库存，需求）              本次使用在途 = min（赶得上的可用在途，需求 − 本次使用库存）
 * 净需求 = 需求 − 本次使用库存 − 本次使用在途        建议采购 = 净需求换成采购单位，按最小包装、起订量取整（Num.suggestPurchase）
 * </pre>
 * 毛需求先不取整、全部加完再取 3 位（中间取整会把误差放大，Num 的约定）。本次使用在途按预计到货从早到晚一条一条分到采购行上
 */
final class MrpCalculator {

    private MrpCalculator() {
    }

    /**
     * 订单的一个 SKU 用 BOM 的一行：件数、这个尺码的单件用量、损耗率、物料来源、BOM 行上的供应商
     *
     * @param source BOM 行的物料来源：PURCHASE / FACTORY / CUSTOMER_SUPPLIED
     */
    record Demand(long orderId, String orderNo, String styleCode, int bomSeq, String source, Long supplierId,
                  long materialId, long skuId, int qty, BigDecimal usage, BigDecimal lossPct) {
    }

    /** 本次使用在途分到的一条采购行（库存单位） */
    record Alloc(long lineId, BigDecimal qty, LocalDate eta) {
    }

    /** 赶不上的一条在途：当时还能用的数 */
    record Late(long lineId, BigDecimal freeQty, LocalDate eta) {
    }

    /** 一行结果：结果行（还没有 id、calcId）、分到的在途、赶不上的在途 */
    record Row(MrpCalcItem item, List<Alloc> allocs, List<Late> lates) {
    }

    /**
     * 别的输入
     *
     * @param infos      SKU → 采购要的东西（换算系数、最小包装、起订量、默认供应商、备料比）
     * @param typePcts   物料类型 → 备料比（字典 material_type 的数值列），物料上没设备料比时用
     * @param prices     参考单价：(供应商, SKU) → 元 / 采购单位（价格档案现价）；没有的退回 SKU 单价、物料参考单价
     * @param needDate   要料日期；null = 算不出来，在途都当赶得上
     * @param excluded   不算"别人"的算料单（自己、上一张）
     * @param prevCalcId 重新算料的上一张（它生成的采购行不当可用的，见 SupplyBook）
     */
    record Inputs(Map<Long, MaterialPurchaseInfo> infos, Map<String, BigDecimal> typePcts, Function<PriceKey, BigDecimal> prices,
                  LocalDate needDate, Set<Long> excluded, Long prevCalcId) {
    }

    record PriceKey(long supplierId, long materialId, long skuId) {
    }

    /** 汇总、扣减，按 我方采购 → 工厂自备、SKU 码排好，seq 从 1 起 */
    static List<Row> calculate(List<Demand> demands, SupplyBook book, Inputs in) {
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        for (Demand d : demands.stream().sorted(Comparator.comparing(Demand::orderId).thenComparing(Demand::bomSeq)).toList()) {
            MrpBlock block = block(d.source());
            if (block == null) {
                continue;
            }
            Group g = groups.computeIfAbsent(List.of(block, d.skuId()), k -> new Group(block, d.skuId()));
            g.gross = g.gross.add(Num.addPct(BigDecimal.valueOf(d.qty()).multiply(d.usage()), d.lossPct()));
            if (g.supplierId == null) {
                g.supplierId = d.supplierId();
            }
            g.orderNos.add(d.orderNo());
            g.styleCodes.add(d.styleCode());
        }
        List<Row> rows = new ArrayList<>();
        for (Group g : groups.values()) {
            rows.add(row(g, book, in));
        }
        rows.sort(Comparator.comparing((Row r) -> r.item().getBlock())
                .thenComparing(r -> skuCode(in, r.item().getMaterialSkuId())));
        for (int n = 0; n < rows.size(); n++) {
            rows.get(n).item().setSeq(n + 1);
        }
        return rows;
    }

    /** 我方采购的算；工厂自备的只列毛需求、需求；客供（TRADE 关时不会有）不进结果 */
    private static MrpBlock block(String source) {
        return switch (source) {
            case "PURCHASE" -> MrpBlock.PURCHASE;
            case "FACTORY" -> MrpBlock.FACTORY;
            default -> null;
        };
    }

    private static Row row(Group g, SupplyBook book, Inputs in) {
        MaterialPurchaseInfo info = in.infos().get(g.skuId);
        MrpCalcItem item = new MrpCalcItem();
        item.setBlock(g.block);
        item.setMaterialSkuId(g.skuId);
        item.setSupplierId(g.supplierId != null ? g.supplierId : info.defaultSupplierId());
        item.setGrossQty(Num.qty(g.gross));
        BigDecimal pct = reservePct(info, in.typePcts());
        item.setReservePct(pct);
        BigDecimal need = Num.qty(Num.addPct(g.gross, pct));
        item.setNeedQty(need);
        item.setPurchaseUnit(info.purchaseUnit());
        item.setConversionFactor(info.conversionFactor());
        item.setOrderNos(joinCapped(g.orderNos));
        item.setStyleCodes(joinCapped(g.styleCodes));
        List<Alloc> allocs = new ArrayList<>();
        List<Late> lates = new ArrayList<>();
        if (g.block != MrpBlock.PURCHASE) {
            zero(item);
            return new Row(item, allocs, lates);
        }

        BigDecimal availStock = book.availStock(g.skuId, in.excluded(), in.prevCalcId());
        BigDecimal useStock = availStock.min(need);
        BigDecimal availTransit = BigDecimal.ZERO;
        BigDecimal lateTransit = BigDecimal.ZERO;
        BigDecimal want = need.subtract(useStock);
        BigDecimal useTransit = BigDecimal.ZERO;
        for (SupplyBook.FreeLine f : book.freeLines(g.skuId, in.excluded(), in.prevCalcId())) {
            if (!catchesUp(f.line().eta(), in.needDate())) {
                lateTransit = lateTransit.add(f.freeQty());
                lates.add(new Late(f.line().id(), f.freeQty(), f.line().eta()));
                continue;
            }
            availTransit = availTransit.add(f.freeQty());
            BigDecimal take = f.freeQty().min(want.subtract(useTransit));
            if (take.signum() > 0) {
                allocs.add(new Alloc(f.line().id(), take, f.line().eta()));
                useTransit = useTransit.add(take);
            }
        }
        item.setAvailStockQty(availStock);
        item.setUseStockQty(useStock);
        item.setAvailTransitQty(availTransit);
        item.setUseTransitQty(useTransit);
        item.setLateTransitQty(lateTransit);
        settle(item, info, in.prices());
        return new Row(item, allocs, lates);
    }

    /** 净需求、建议采购、参考单价、预计金额：建好时、草稿里改了本次使用、换了供应商以后都按它重算 */
    static void settle(MrpCalcItem item, MaterialPurchaseInfo info, Function<PriceKey, BigDecimal> prices) {
        BigDecimal net = item.getNeedQty().subtract(item.getUseStockQty()).subtract(item.getUseTransitQty()).max(BigDecimal.ZERO);
        item.setNetQty(net);
        item.setSuggestQty(Num.suggestPurchase(net, item.getConversionFactor(), info.minPackQty(), info.moq()));
        reprice(item, info, prices);
    }

    /** 参考单价：这家供应商的价格档案现价，没有用 SKU 单价、物料参考单价；预计金额 = 建议采购 × 参考单价 */
    static void reprice(MrpCalcItem item, MaterialPurchaseInfo info, Function<PriceKey, BigDecimal> prices) {
        BigDecimal archived = item.getSupplierId() == null ? null
                : prices.apply(new PriceKey(item.getSupplierId(), info.materialId(), info.skuId()));
        item.setRefPrice(Num.price(archived != null ? archived : info.price()));
        item.setEstAmount(Num.amount(item.getSuggestQty(), item.getRefPrice()));
    }

    /** 备料比：物料上设的优先，没设按物料类型，都没有是 0（Q18、Q31） */
    static BigDecimal reservePct(MaterialPurchaseInfo info, Map<String, BigDecimal> typePcts) {
        if (info.reservePct() != null) {
            return info.reservePct();
        }
        BigDecimal byType = info.materialType() == null ? null : typePcts.get(info.materialType());
        return byType != null ? byType : BigDecimal.ZERO;
    }

    /** 赶得上：预计到货不晚于要料日期；要料日期算不出来的都当赶得上 */
    static boolean catchesUp(LocalDate eta, LocalDate needDate) {
        return needDate == null || eta == null || !eta.isAfter(needDate);
    }

    /** 工厂自备的行只列需求，别的数都是 0 */
    private static void zero(MrpCalcItem item) {
        item.setAvailStockQty(BigDecimal.ZERO);
        item.setUseStockQty(BigDecimal.ZERO);
        item.setAvailTransitQty(BigDecimal.ZERO);
        item.setUseTransitQty(BigDecimal.ZERO);
        item.setLateTransitQty(BigDecimal.ZERO);
        item.setNetQty(BigDecimal.ZERO);
        item.setSuggestQty(BigDecimal.ZERO);
    }

    /** 逗号连起来；列是 500 字，几十张订单一起算时截掉后面的（只是冗余显示，全的在 mrp_calc_order） */
    static String joinCapped(Set<String> values) {
        String joined = String.join(",", values);
        return joined.length() <= 500 ? joined : joined.substring(0, 499) + "…";
    }

    private static String skuCode(Inputs in, long skuId) {
        MaterialPurchaseInfo info = in.infos().get(skuId);
        return info == null ? "" : Objects.toString(info.skuCode(), "");
    }

    /** 汇总中的一个 (块, SKU) */
    private static final class Group {
        final MrpBlock block;
        final long skuId;
        BigDecimal gross = BigDecimal.ZERO;
        Long supplierId;
        final Set<String> orderNos = new LinkedHashSet<>();
        final Set<String> styleCodes = new LinkedHashSet<>();

        Group(MrpBlock block, long skuId) {
            this.block = block;
            this.skuId = skuId;
        }
    }
}
