package com.lingmao.scm.module.purchase.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购超收冲抵（RFC 014 §2）：一次入库、退货过账（或反审核）以后，哪些冲抵要退回、还回去、新冲。纯函数，不碰库，单测直接喂数；
 * 取数、写回在 {@link PurchaseOffsetBook}。数量都是采购单位。
 * <p>
 * 到货 = 净入库 + 冲入 − 冲出。按这个顺序算，算完一行不会同时有冲入和冲出，所以退回、还回都只走一步，不会一路连下去：
 * <ol>
 *   <li><b>退回</b>：这次过账的行到货不够采购数了、它又冲出过的，把冲出的从最近一笔往前退回来，退到它重新到齐或者冲出的退完；</li>
 *   <li><b>还回</b>：这次过账的行到货超过采购数了、它又有冲入的，先把冲入的从最近一笔往前还给多收的那一行
 *       （靠冲入到齐的单，供应商照单再送来的，入了就把冲来的还回去）；</li>
 *   <li><b>冲</b>：还有多收的，冲给同一组没到齐的行（已审核、部分到货），先冲下单早的，同一天按单号、行号。
 *       冲的只是这次入的那部分；还回来的那部分只冲给多收那一刻以前就审核了的单（RFC 014 §1"先超收、后下的单不冲"）。
 *       有时间限制的（还回来的）先冲、早的先冲，这次入的不受限制、最后冲：反过来，这次入的会先占掉还回来的能去的单，还回来的就冲不出去了。</li>
 * </ol>
 */
final class OverOffsetPlan {

    private OverOffsetPlan() {
    }

    /**
     * 一条采购行现在的数（这次过账回写净入库以后）
     *
     * @param group     同一组才冲：主体、供应商、品类线、SKU、采购单位、换算系数拼起来的
     * @param auditTime 采购单审核的时间：还回来的多收只冲给这以前审核的
     * @param open      采购单是已审核、部分到货（能被冲）
     */
    record Line(long id, long poId, String poNo, String group, LocalDate orderDate, int seq, OffsetDateTime auditTime,
                boolean open, BigDecimal qty, BigDecimal net, BigDecimal in, BigDecimal out) {
    }

    /** 一笔冲抵：from 那一行多收的 qty 冲给了 to；createTime 是多收那一刻 */
    record Offset(long id, long fromId, long toId, BigDecimal qty, OffsetDateTime createTime) {
    }

    /**
     * 一对行之间这次变了多少：新冲的、退回的、还回的都按"from 冲给 to"记，qty > 0。
     * since 是新冲的这笔记成哪一刻多收的：null = 这次入的（现在）；还回来再冲的照原来那笔的时间，以后再还回、再冲还按它卡
     */
    record Move(long fromId, long toId, BigDecimal qty, OffsetDateTime since) {

        Move(long fromId, long toId, BigDecimal qty) {
            this(fromId, toId, qty, null);
        }
    }

    /**
     * @param updated  改了数的冲抵：id → 新的数，0 就删掉
     * @param added    新冲的（同一对、同一个多收时间合成一笔）
     * @param undone   退回、还回的（同一对合成一笔，since 是 null）
     * @param inDelta  每行冲入变了多少
     * @param outDelta 每行冲出变了多少
     */
    record Result(Map<Long, BigDecimal> updated, List<Move> added, List<Move> undone, Map<Long, BigDecimal> inDelta,
                  Map<Long, BigDecimal> outDelta) {

        boolean isEmpty() {
            return updated.isEmpty() && added.isEmpty();
        }

        /** 冲入、冲出变了的行 */
        Set<Long> touchedLines() {
            Set<Long> ids = new HashSet<>(inDelta.keySet());
            ids.addAll(outDelta.keySet());
            return ids;
        }
    }

    /** 多收的一份：哪一行、最多冲多少、从哪一刻起（null = 这次入的，现在开着的单都能冲） */
    private record Surplus(long lineId, BigDecimal qty, OffsetDateTime since) {
    }

    /**
     * @param lines   这次过账的行、和它们冲过的行、同组没到齐的行（只给锁住了的）
     * @param offsets 这次过账的行上的冲抵（from 或 to 是它们）
     * @param posted  这次过账每行净入库变了多少（入库、反审核退货为正，反审核入库、退货为负）
     */
    static Result plan(Collection<Line> lines, Collection<Offset> offsets, Map<Long, BigDecimal> posted) {
        State s = new State(lines, offsets);
        List<Long> ids = posted.keySet().stream().filter(s.lines::containsKey).sorted().toList();
        for (long id : ids) {
            s.undoOut(id);
        }
        List<Surplus> surplus = new ArrayList<>();
        for (long id : ids) {
            surplus.addAll(s.giveBack(id));
        }
        List<Surplus> order = new ArrayList<>(surplus.stream().sorted(Comparator.comparing(Surplus::since)).toList());
        for (long id : ids) {
            BigDecimal delta = posted.get(id);
            if (delta != null && delta.signum() > 0) {
                order.add(new Surplus(id, delta, null));
            }
        }
        for (Surplus x : order) {
            s.spread(x);
        }
        return s.result();
    }

    private static final class State {
        final Map<Long, Line> lines = new LinkedHashMap<>();
        final Map<Long, BigDecimal> in = new HashMap<>();
        final Map<Long, BigDecimal> out = new HashMap<>();
        final List<Offset> offsets;
        final Map<Long, BigDecimal> qtyOf = new HashMap<>();
        final Map<Long, BigDecimal> inDelta = new HashMap<>();
        final Map<Long, BigDecimal> outDelta = new HashMap<>();
        final Map<List<Object>, BigDecimal> added = new LinkedHashMap<>();
        final Map<List<Object>, BigDecimal> undone = new LinkedHashMap<>();

        State(Collection<Line> lines, Collection<Offset> offsets) {
            lines.forEach(l -> {
                this.lines.put(l.id(), l);
                in.put(l.id(), l.in());
                out.put(l.id(), l.out());
            });
            this.offsets = offsets.stream().sorted(Comparator.comparing(Offset::createTime).thenComparing(Offset::id).reversed()).toList();
            offsets.forEach(o -> qtyOf.put(o.id(), o.qty()));
        }

        BigDecimal arrived(long id) {
            Line l = lines.get(id);
            return l.net().add(in.get(id)).subtract(out.get(id));
        }

        /** 第 1 步：到货不够了，把冲出去的从最近一笔往前退回来 */
        void undoOut(long id) {
            Line l = lines.get(id);
            BigDecimal need = l.qty().subtract(arrived(id)).min(out.get(id));
            for (Offset o : offsets) {
                if (need.signum() <= 0) {
                    return;
                }
                if (o.fromId() != id || !lines.containsKey(o.toId())) {
                    continue;
                }
                BigDecimal take = need.min(qtyOf.get(o.id()));
                if (take.signum() > 0) {
                    move(o, take, undone);
                    need = need.subtract(take);
                }
            }
        }

        /** 第 2 步：到货超了、又有冲入的，先把冲入的还回去；还回去的那一行多出来的，按原来多收那一刻再冲 */
        List<Surplus> giveBack(long id) {
            Line l = lines.get(id);
            BigDecimal excess = arrived(id).subtract(l.qty()).min(in.get(id));
            List<Surplus> back = new ArrayList<>();
            for (Offset o : offsets) {
                if (excess.signum() <= 0) {
                    break;
                }
                if (o.toId() != id || !lines.containsKey(o.fromId())) {
                    continue;
                }
                BigDecimal take = excess.min(qtyOf.get(o.id()));
                if (take.signum() > 0) {
                    move(o, take, undone);
                    excess = excess.subtract(take);
                    back.add(new Surplus(o.fromId(), take, o.createTime()));
                }
            }
            return back;
        }

        /** 退回、还回一笔：这笔冲抵改小，两头的冲入、冲出跟着减 */
        private void move(Offset o, BigDecimal take, Map<List<Object>, BigDecimal> log) {
            qtyOf.merge(o.id(), take.negate(), BigDecimal::add);
            change(o.fromId(), out, outDelta, take.negate());
            change(o.toId(), in, inDelta, take.negate());
            log.merge(key(o.fromId(), o.toId(), null), take, BigDecimal::add);
        }

        /** 同一对、同一个多收时间的合成一笔（List.of 不收 null，换成 Arrays.asList） */
        private static List<Object> key(long from, long to, OffsetDateTime since) {
            return java.util.Arrays.asList(from, to, since);
        }

        /** 第 3 步：多收的冲给同组没到齐的，先冲下单早的 */
        void spread(Surplus x) {
            Line from = lines.get(x.lineId());
            if (in.get(from.id()).signum() > 0) {
                return;
            }
            BigDecimal left = x.qty().min(arrived(from.id()).subtract(from.qty()));
            if (left.signum() <= 0) {
                return;
            }
            List<Line> targets = lines.values().stream()
                    .filter(t -> t.id() != from.id() && t.group().equals(from.group()) && t.open() && out.get(t.id()).signum() == 0
                            && arrived(t.id()).compareTo(t.qty()) < 0
                            && (x.since() == null || t.auditTime() != null && !t.auditTime().isAfter(x.since())))
                    .sorted(Comparator.comparing(Line::orderDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Line::poNo).thenComparingInt(Line::seq).thenComparingLong(Line::id))
                    .toList();
            for (Line t : targets) {
                if (left.signum() <= 0) {
                    return;
                }
                BigDecimal take = left.min(t.qty().subtract(arrived(t.id())));
                change(from.id(), out, outDelta, take);
                change(t.id(), in, inDelta, take);
                added.merge(key(from.id(), t.id(), x.since()), take, BigDecimal::add);
                left = left.subtract(take);
            }
        }

        private static void change(long id, Map<Long, BigDecimal> now, Map<Long, BigDecimal> delta, BigDecimal by) {
            now.merge(id, by, BigDecimal::add);
            delta.merge(id, by, BigDecimal::add);
        }

        Result result() {
            Map<Long, BigDecimal> updated = new LinkedHashMap<>();
            offsets.forEach(o -> {
                if (qtyOf.get(o.id()).compareTo(o.qty()) != 0) {
                    updated.put(o.id(), qtyOf.get(o.id()));
                }
            });
            inDelta.values().removeIf(v -> v.signum() == 0);
            outDelta.values().removeIf(v -> v.signum() == 0);
            return new Result(updated, moves(added), moves(undone), inDelta, outDelta);
        }

        private static List<Move> moves(Map<List<Object>, BigDecimal> m) {
            return m.entrySet().stream().map(e -> new Move((Long) e.getKey().get(0), (Long) e.getKey().get(1), e.getValue(),
                    (OffsetDateTime) e.getKey().get(2))).toList();
        }
    }
}
