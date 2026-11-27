package com.lingmao.scm.module.settle.service.impl;

import com.lingmao.scm.module.settle.dto.CompareResult.Ours;
import com.lingmao.scm.module.settle.dto.CompareResult.Outcome;
import com.lingmao.scm.module.settle.dto.CompareResult.Row;
import com.lingmao.scm.module.settle.dto.CompareResult.Their;
import com.lingmao.scm.module.settle.entity.StatementItem;
import com.lingmao.scm.module.settle.entity.StatementSourceType;
import com.lingmao.scm.module.settle.service.impl.TheirSheetReader.TheirRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 对方的对账单和我方的对账单逐处比（RFC 019 §5.3）。两边都按单据合起来比：
 * 对方同一个单号的几行合成一张，我方同一张入库单、退货单的几行合成一张，回货单本来就是一行。
 * <ol>
 *   <li>按单号：对方的单号 = 我方单号、供应商送货单号；或者对方的单号、订单号 = 我方的采购单号、生产单号（几张都对得上时挑数量一样、日期最近的）</li>
 *   <li>日期前后 3 天内、数量一样的</li>
 *   <li>日期前后 3 天内、金额一样的</li>
 * </ol>
 * 回货有次品时，对方写的可能是含次品的金额，也可能是扣了次品的：哪个对上算哪个，扣了的那部分次品扣款就不再进"扣款合计"比。
 * 扣款两边各自合起来比一处。差 0.01 元以内算一致
 */
final class StatementComparer {

    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.001");
    private static final int DAY_WINDOW = 3;

    record Compared(List<Row> rows, BigDecimal theirGoods, BigDecimal theirDeduct, int theirRowCount) {
        BigDecimal theirTotal() {
            return theirGoods.subtract(theirDeduct);
        }
    }

    /** 我方的一张单据 */
    private static final class OurDoc {
        final StatementSourceType type;
        final long sourceId;
        final String no;
        final String deliveryNo;
        final String orderNo;
        final LocalDate date;
        final List<String> descriptions = new ArrayList<>();
        BigDecimal qty;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal deduct = BigDecimal.ZERO;
        boolean used;

        OurDoc(StatementItem i) {
            type = i.getSourceType();
            sourceId = i.getSourceId();
            no = i.getSourceNo();
            deliveryNo = i.getDeliveryNo();
            orderNo = i.getOrderNo();
            date = i.getBizDate();
        }

        void add(StatementItem i) {
            if (i.getDescription() != null && !i.getDescription().isBlank()) {
                descriptions.add(i.getDescription());
            }
            if (i.getQty() != null) {
                qty = qty == null ? i.getQty() : qty.add(i.getQty());
            }
            amount = amount.add(nz(i.getAmount()));
            deduct = deduct.add(nz(i.getDeductAmount()));
        }

        String description() {
            if (descriptions.isEmpty()) {
                return null;
            }
            return descriptions.size() == 1 ? descriptions.getFirst() : descriptions.getFirst() + " 等 " + descriptions.size() + " 行";
        }

        BigDecimal net() {
            return amount.subtract(deduct);
        }
    }

    /** 对方的一张单据（同一个单号的几行）或没写单号的一行 */
    private static final class TheirDoc {
        final List<Integer> rowNos = new ArrayList<>();
        final String no;
        String orderNo;
        LocalDate date;
        final List<String> descriptions = new ArrayList<>();
        BigDecimal qty;
        BigDecimal amount = BigDecimal.ZERO;

        TheirDoc(String no) {
            this.no = no;
        }

        void add(TheirRow r) {
            rowNos.add(r.rowNo());
            if (orderNo == null && !blank(r.orderNo())) {
                orderNo = r.orderNo();
            }
            if (date == null) {
                date = r.date();
            }
            if (!blank(r.description())) {
                descriptions.add(r.description());
            }
            if (r.qty() != null) {
                qty = qty == null ? r.qty() : qty.add(r.qty());
            }
            amount = amount.add(r.amount());
        }

        Their view() {
            String desc = descriptions.isEmpty() ? null
                    : descriptions.size() == 1 ? descriptions.getFirst() : descriptions.getFirst() + " 等 " + descriptions.size() + " 行";
            return new Their(List.copyOf(rowNos), blank(no) ? orderNo : no, date, desc, qty, amount, false);
        }
    }

    private StatementComparer() {
    }

    static Compared compare(List<TheirRow> theirRows, List<StatementItem> items) {
        // 我方：按单据合起来，扣款登记单独合计
        Map<String, OurDoc> ourMap = new LinkedHashMap<>();
        BigDecimal ourDeduct = BigDecimal.ZERO;
        for (StatementItem i : items) {
            if (i.getSourceType() == StatementSourceType.DEDUCTION) {
                ourDeduct = ourDeduct.add(nz(i.getDeductAmount()));
                continue;
            }
            ourMap.computeIfAbsent(i.getSourceType() + ":" + i.getSourceId(), k -> new OurDoc(i)).add(i);
        }
        List<OurDoc> ours = new ArrayList<>(ourMap.values());

        // 对方：有单号的按单号合起来，没单号的一行一张；扣款合计。
        // 对方写的是我方的采购单号、生产单号（不是哪张入库单、回货单的单号）时，一张订单分几次送货：按单号 + 日期分开，
        // 不然几次送货合成一张，只对上我方一张入库单（agy 复查 T-adeaba #7）
        Set<String> ourDocNos = new HashSet<>();
        Set<String> ourOrderNos = new HashSet<>();
        for (OurDoc o : ours) {
            ourDocNos.add(norm(o.no));
            ourDocNos.add(norm(o.deliveryNo));
            ourOrderNos.add(norm(o.orderNo));
        }
        ourDocNos.remove("");
        ourOrderNos.remove("");
        Map<String, TheirDoc> byNo = new LinkedHashMap<>();
        List<TheirDoc> theirs = new ArrayList<>();
        BigDecimal theirGoods = BigDecimal.ZERO;
        BigDecimal theirDeduct = BigDecimal.ZERO;
        List<Integer> deductRows = new ArrayList<>();
        for (TheirRow r : theirRows) {
            if (r.deduct()) {
                theirDeduct = theirDeduct.add(r.amount());
                deductRows.add(r.rowNo());
                continue;
            }
            theirGoods = theirGoods.add(r.amount());
            if (blank(r.no())) {
                TheirDoc d = new TheirDoc(null);
                d.add(r);
                theirs.add(d);
            } else {
                String key = norm(r.no());
                if (!ourDocNos.contains(key) && ourOrderNos.contains(key)) {
                    key = key + "@" + r.date();
                }
                byNo.computeIfAbsent(key, k -> {
                    TheirDoc d = new TheirDoc(r.no());
                    theirs.add(d);
                    return d;
                }).add(r);
            }
        }

        Map<TheirDoc, OurDoc> hit = new LinkedHashMap<>();
        // 1. 单号
        for (TheirDoc t : theirs) {
            OurDoc o = byNumber(t, ours);
            if (o != null) {
                o.used = true;
                hit.put(t, o);
            }
        }
        // 2. 日期前后 3 天 + 数量一样；3. 日期前后 3 天 + 金额一样
        List<BiPredicate<TheirDoc, OurDoc>> passes = List.of(
                (t, o) -> t.qty != null && o.qty != null && t.qty.subtract(o.qty).abs().compareTo(QTY_TOLERANCE) < 0,
                (t, o) -> sameAmount(t.amount, o.amount) || sameAmount(t.amount, o.net()));
        for (BiPredicate<TheirDoc, OurDoc> same : passes) {
            for (TheirDoc t : theirs) {
                if (hit.containsKey(t) || t.date == null) {
                    continue;
                }
                OurDoc best = null;
                long bestDays = Long.MAX_VALUE;
                for (OurDoc o : ours) {
                    if (o.used || o.date == null) {
                        continue;
                    }
                    long days = Math.abs(ChronoUnit.DAYS.between(t.date, o.date));
                    if (days <= DAY_WINDOW && days < bestDays && same.test(t, o)) {
                        best = o;
                        bestDays = days;
                    }
                }
                if (best != null) {
                    best.used = true;
                    hit.put(t, best);
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        BigDecimal netted = BigDecimal.ZERO;
        for (TheirDoc t : theirs) {
            OurDoc o = hit.get(t);
            if (o == null) {
                rows.add(new Row(null, Outcome.THEIRS_ONLY, t.view(), null, null, t.amount, null));
                continue;
            }
            // 回货有次品：对方写的是扣了次品的金额，就按扣了的比，这部分扣款不再进扣款合计
            BigDecimal ourAmount = o.amount;
            if (o.deduct.signum() != 0 && !sameAmount(t.amount, o.amount) && sameAmount(t.amount, o.net())) {
                ourAmount = o.net();
                netted = netted.add(o.deduct);
            }
            BigDecimal diffQty = t.qty != null && o.qty != null ? t.qty.subtract(o.qty) : null;
            BigDecimal diffAmount = t.amount.subtract(ourAmount);
            boolean same = (diffQty == null || diffQty.abs().compareTo(QTY_TOLERANCE) < 0) && diffAmount.abs().compareTo(AMOUNT_TOLERANCE) <= 0;
            rows.add(new Row(null, same ? Outcome.MATCH : Outcome.DIFF, t.view(), ours(o, ourAmount), diffQty, diffAmount, null));
        }
        for (OurDoc o : ours) {
            if (!o.used) {
                rows.add(new Row(null, Outcome.OURS_ONLY, null, ours(o, o.amount), null, o.amount.negate(), null));
            }
        }
        rows.sort(Comparator.comparing(StatementComparer::dateOf, Comparator.nullsLast(Comparator.naturalOrder())));

        // 扣款合计：我方 = 扣款登记 + 回货次品（对方按扣了次品写的那几张不算）
        BigDecimal receiptDeduct = ours.stream().map(o -> o.deduct).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ourDeductTotal = ourDeduct.add(receiptDeduct).subtract(netted);
        if (ourDeductTotal.signum() != 0 || theirDeduct.signum() != 0) {
            BigDecimal diff = theirDeduct.subtract(ourDeductTotal);
            Outcome outcome = diff.abs().compareTo(AMOUNT_TOLERANCE) <= 0 ? Outcome.MATCH
                    : theirDeduct.signum() == 0 ? Outcome.OURS_ONLY : ourDeductTotal.signum() == 0 ? Outcome.THEIRS_ONLY : Outcome.DIFF;
            Their their = theirDeduct.signum() == 0 ? null : new Their(List.copyOf(deductRows), null, null, "扣款合计", null, theirDeduct, true);
            Ours our = ourDeductTotal.signum() == 0 ? null
                    : new Ours(StatementSourceType.DEDUCTION, null, null, null, null, "扣款合计（扣款登记、回货次品）", null, ourDeductTotal);
            // 扣款少扣了，对方的应付就多：金额差按"对方应付 − 我方应付"的方向记
            rows.add(new Row(null, outcome, their, our, null, ourDeductTotal.subtract(theirDeduct), null));
        }

        List<Row> keyed = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            keyed.add(new Row(String.valueOf(i + 1), r.outcome(), r.their(), r.ours(), r.diffQty(), r.diffAmount(), null));
        }
        return new Compared(keyed, theirGoods, theirDeduct, theirRows.size());
    }

    /** 按单号找：单号、送货单号对上的优先；再按采购单号、生产单号（对得上几张时挑数量一样、日期最近的） */
    private static OurDoc byNumber(TheirDoc t, List<OurDoc> ours) {
        String no = norm(t.no);
        String orderNo = norm(t.orderNo);
        OurDoc direct = nearest(t, ours.stream().filter(o -> !o.used && !no.isEmpty()
                && (no.equals(norm(o.no)) || no.equals(norm(o.deliveryNo)))).toList());
        if (direct != null) {
            return direct;
        }
        List<OurDoc> byOrder = ours.stream().filter(o -> !o.used && !norm(o.orderNo).isEmpty()
                && (norm(o.orderNo).equals(no) || norm(o.orderNo).equals(orderNo))).toList();
        List<OurDoc> sameQty = byOrder.stream().filter(o -> t.qty != null && o.qty != null
                && t.qty.subtract(o.qty).abs().compareTo(QTY_TOLERANCE) < 0).toList();
        return nearest(t, sameQty.isEmpty() ? byOrder : sameQty);
    }

    private static OurDoc nearest(TheirDoc t, List<OurDoc> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (t.date == null) {
            return candidates.getFirst();
        }
        return candidates.stream().min(Comparator.comparingLong(o -> o.date == null ? Long.MAX_VALUE
                : Math.abs(ChronoUnit.DAYS.between(t.date, o.date)))).orElseThrow();
    }

    private static Ours ours(OurDoc o, BigDecimal amount) {
        return new Ours(o.type, o.sourceId, o.no, o.deliveryNo, o.date, o.description(), o.qty, amount);
    }

    private static LocalDate dateOf(Row r) {
        if (r.their() != null && r.their().date() != null) {
            return r.their().date();
        }
        return r.ours() == null ? null : r.ours().date();
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 单号比较：去空白、横杠，大写 */
    static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-_/]", "").toUpperCase(Locale.ROOT);
    }
}
