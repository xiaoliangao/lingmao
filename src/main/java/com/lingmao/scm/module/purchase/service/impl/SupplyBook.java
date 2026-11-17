package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.num.Num;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一批物料 SKU 的库存、在途和软占用（架构 §5.4、PRD §6.6.1、§6.6.7）。数据由 SupplyMapper 一次取齐，这里只算，不碰库，单测直接喂数。
 * 数量都换成库存单位（采购行的数 × 换算系数）。
 * <p>
 * <b>占着的</b>：已确认（没关闭）的算料单，按来源分三份：
 * <ul>
 *   <li>本次使用库存：占着库存；</li>
 *   <li>本次使用在途（mrp_calc_transit）：一条采购行上几张算料单用了 U，这行还没到的 P：min(P, U) 还是占着的在途，
 *       剩下 U − min(P, U) 已经到了，变成占着的库存。到了的先算给先确认的（use id 小的）；</li>
 *   <li>它自己生成的采购行（calc_item_id 指着它的行）：没到的整条占着在途，到了的（净入库）占着库存。</li>
 * </ul>
 * 一张算料单占着的库存再减去它的订单已经领走的（issued_qty），不小于 0。只算送物料仓的：直发工厂仓的料不进公共的数（Q11）。
 * <p>
 * <b>排除</b>：算某张单的可用量时，它自己（确认前再算一遍时）和它的上一张（重新算料时，docs/api/03 §3.2.9）不算"别人"。
 * 上一张<b>自己生成的采购行</b>不进可用库存、可用在途：新单确认时这些行连同已转采购转到新单（§3.2.7），新单要买的是"建议采购 − 已转采购"，
 * 它们再当成可用的就重复算了一遍
 */
final class SupplyBook {

    /**
     * 一条采购行（只取用得着的：没入齐的，或者被已确认的算料单占着、用着的，或者是上一张生成的）。数量是采购单位
     *
     * @param open           采购单是已审核、部分到货（没到的才算在途）
     * @param toMaterial     交货地点是物料仓
     * @param ownerCalcId    哪张算料单生成的（calc_item_id 指着的行所在的单）；手建的、补货生成的是 null
     * @param ownerConfirmed 那张算料单现在是已确认
     */
    record Line(long id, long poId, String poNo, String purchaseType, long skuId, long warehouseId, BigDecimal qty,
                BigDecimal receivedQty, BigDecimal returnedQty, BigDecimal factor, LocalDate eta, boolean open,
                boolean toMaterial, Long ownerItemId, Long ownerCalcId, boolean ownerConfirmed) {

        /** 净入库，库存单位 */
        BigDecimal receivedStock() {
            return Num.qty(receivedQty.subtract(returnedQty).max(BigDecimal.ZERO).multiply(factor));
        }

        /** 没到的，库存单位：采购单不是已审核、部分到货的（结案了）不算在途 */
        BigDecimal pendingStock() {
            return open ? remainingStock() : BigDecimal.ZERO;
        }

        /** 采购数 − 净入库，库存单位，不看采购单状态：结案了没到的那部分也永远到不了，算"使用在途到了多少"时要按它，不能当成到了 */
        BigDecimal remainingStock() {
            return Num.qty(qty.subtract(receivedQty.subtract(returnedQty)).max(BigDecimal.ZERO).multiply(factor));
        }
    }

    /** 已确认的算料单的一条本次使用在途（库存单位） */
    record Use(long id, long calcId, long calcItemId, long lineId, BigDecimal qty) {
    }

    /** 已确认的算料单"我方采购"的一行：本次使用库存、已领 */
    record HeldItem(long id, long calcId, long skuId, BigDecimal useStockQty, BigDecimal issuedQty) {
    }

    /** 能用的一条在途：还能用的数、预计到货（算料按它分本次使用在途、列赶不上的在途） */
    record FreeLine(Line line, BigDecimal freeQty) {
    }

    private final Map<Long, BigDecimal> stock;
    private final Map<Long, Line> lines = new HashMap<>();
    private final List<Use> uses;
    private final List<HeldItem> items;

    /** 每条采购行上已经到了的、算给哪条使用（use id → 已到） */
    private final Map<Long, BigDecimal> arrivedOfUse = new HashMap<>();

    /**
     * @param stock 物料仓库存（自有），skuId → 库存单位
     * @param uses  已确认的算料单的本次使用在途
     * @param items 已确认的算料单"我方采购"的行
     */
    SupplyBook(Map<Long, BigDecimal> stock, Collection<Line> lines, List<Use> uses, List<HeldItem> items) {
        this.stock = stock;
        lines.forEach(l -> this.lines.put(l.id(), l));
        this.uses = uses;
        this.items = items;
        Map<Long, List<Use>> byLine = new HashMap<>();
        uses.forEach(u -> byLine.computeIfAbsent(u.lineId(), k -> new ArrayList<>()).add(u));
        byLine.forEach((lineId, list) -> {
            Line l = this.lines.get(lineId);
            BigDecimal pending = l == null ? BigDecimal.ZERO : l.remainingStock();
            BigDecimal used = sum(list.stream().map(Use::qty).toList());
            // 用的时候用的是没到的那部分；之后到的先算给没人用的那部分，到了的 = 用了的 − 还没到的，按 use id 从小到大分。
            // 这样算不用记"用的时候已经到了多少"，也不会把还没到的算成到了
            BigDecimal arrived = used.subtract(pending).max(BigDecimal.ZERO);
            for (Use u : list.stream().sorted(Comparator.comparing(Use::id)).toList()) {
                BigDecimal a = arrived.min(u.qty());
                arrivedOfUse.put(u.id(), a);
                arrived = arrived.subtract(a);
            }
        });
    }

    /** 这条使用在途已经到了多少（库存单位）；不是已确认的算料单的使用（草稿里的）按 0 */
    BigDecimal arrivedOf(long useId) {
        return arrivedOfUse.getOrDefault(useId, BigDecimal.ZERO);
    }

    Line line(long id) {
        return lines.get(id);
    }

    /** 物料仓库存 */
    BigDecimal stockOf(long skuId) {
        return stock.getOrDefault(skuId, BigDecimal.ZERO);
    }

    /**
     * 别的算料单占着的库存：每张单 max(0, 本次使用库存 + 用的在途里已到的 + 自己的采购行已入物料仓的 − 已领)。
     * excluded 里的单不算
     */
    BigDecimal heldStock(long skuId, Set<Long> excluded) {
        Map<Long, BigDecimal> byCalc = new HashMap<>();
        for (HeldItem i : items) {
            if (i.skuId() == skuId && !excluded.contains(i.calcId())) {
                byCalc.merge(i.calcId(), i.useStockQty().subtract(i.issuedQty()), BigDecimal::add);
            }
        }
        for (Use u : uses) {
            Line l = lines.get(u.lineId());
            if (l != null && l.skuId() == skuId && l.toMaterial() && !excluded.contains(u.calcId())) {
                byCalc.merge(u.calcId(), arrivedOf(u.id()), BigDecimal::add);
            }
        }
        for (Line l : lines.values()) {
            if (l.skuId() == skuId && l.toMaterial() && l.ownerConfirmed() && !excluded.contains(l.ownerCalcId())) {
                byCalc.merge(l.ownerCalcId(), l.receivedStock(), BigDecimal::add);
            }
        }
        return sum(byCalc.values().stream().map(v -> v.max(BigDecimal.ZERO)).toList());
    }

    /** 上一张（重新算料）自己生成的采购行已经入了物料仓的：新单不当它是可用库存（见类注释） */
    BigDecimal ownReceivedOf(long skuId, Long calcId) {
        if (calcId == null) {
            return BigDecimal.ZERO;
        }
        return sum(lines.values().stream()
                .filter(l -> l.skuId() == skuId && l.toMaterial() && calcId.equals(l.ownerCalcId()))
                .map(Line::receivedStock).toList());
    }

    /** 可用库存 = 物料仓库存 − 别人占着的 − 上一张自己买到的，不小于 0 */
    BigDecimal availStock(long skuId, Set<Long> excluded, Long prevCalcId) {
        return stockOf(skuId).subtract(heldStock(skuId, excluded)).subtract(ownReceivedOf(skuId, prevCalcId)).max(BigDecimal.ZERO);
    }

    /** 一条在途上别人占着的：它是没排除的已确认算料单生成的，整条没到的都占着；不然是别人用着的（不超过没到的） */
    BigDecimal heldTransit(Line l, Set<Long> excluded) {
        BigDecimal pending = l.pendingStock();
        if (l.ownerConfirmed() && !excluded.contains(l.ownerCalcId())) {
            return pending;
        }
        BigDecimal used = sum(uses.stream().filter(u -> u.lineId() == l.id() && !excluded.contains(u.calcId())).map(Use::qty).toList());
        return pending.min(used);
    }

    /**
     * 这个 SKU 送物料仓、还能用的在途（> 0 的），按预计到货、行 id 排。上一张生成的行不在里面（见类注释）。
     * 赶不赶得上由调用方按要料日期分
     */
    List<FreeLine> freeLines(long skuId, Set<Long> excluded, Long prevCalcId) {
        return lines.values().stream()
                .filter(l -> l.skuId() == skuId && l.toMaterial() && l.pendingStock().signum() > 0
                        && (prevCalcId == null || !prevCalcId.equals(l.ownerCalcId())))
                .map(l -> new FreeLine(l, l.pendingStock().subtract(heldTransit(l, excluded))))
                .filter(f -> f.freeQty().signum() > 0)
                .sorted(Comparator.comparing((FreeLine f) -> f.line().eta(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(f -> f.line().id()))
                .toList();
    }

    /** 送物料仓的全部在途（已审核、没入齐），补货提醒用：不看赶不赶得上 */
    BigDecimal transitToMaterial(long skuId) {
        return sum(lines.values().stream().filter(l -> l.skuId() == skuId && l.toMaterial()).map(Line::pendingStock).toList());
    }

    /** 按交货仓库分的在途（库存页的"在途"列），只列 > 0 的 */
    Map<Long, BigDecimal> transitByWarehouse(long skuId) {
        Map<Long, BigDecimal> out = new HashMap<>();
        lines.values().stream().filter(l -> l.skuId() == skuId && l.pendingStock().signum() > 0)
                .forEach(l -> out.merge(l.warehouseId(), l.pendingStock(), BigDecimal::add));
        return out;
    }

    /** 已确认的算料单占着的全部（库存 + 送物料仓的在途），补货提醒、库存页用 */
    BigDecimal heldTotal(long skuId) {
        BigDecimal transit = sum(lines.values().stream().filter(l -> l.skuId() == skuId && l.toMaterial())
                .map(l -> heldTransit(l, Set.of())).toList());
        return heldStock(skuId, Set.of()).add(transit);
    }

    /** 一张算料单的这一行用了哪些在途（给详情、齐料用） */
    List<Use> usesOf(long calcItemId) {
        return uses.stream().filter(u -> u.calcItemId() == calcItemId).toList();
    }

    static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
