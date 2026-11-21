package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.purchase.service.impl.OverOffsetPlan.Line;
import com.lingmao.scm.module.purchase.service.impl.OverOffsetPlan.Move;
import com.lingmao.scm.module.purchase.service.impl.OverOffsetPlan.Offset;
import com.lingmao.scm.module.purchase.service.impl.OverOffsetPlan.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 采购超收冲抵怎么算（RFC 014 §2、§8）。小账本 Book 照 PurchaseOffsetBook 的样子把结果写回去，好一步步往下走 */
class OverOffsetPlanTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.ofHours(8));

    /** §2 的例子：同一组两张单各 1000，在途 950 → 500 → 530 → 0 */
    @Test
    void rfcExample_stepByStep() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);

        Result r = b.post(1, "1050");
        assertThat(mv(r.added())).containsExactly(move(1, 2, "50"));
        assertThat(b.transit()).isEqualByComparingTo("950");

        r = b.post(2, "450");
        assertThat(r.isEmpty()).isTrue();
        assertThat(b.transit()).isEqualByComparingTo("500");

        r = b.post(1, "-30");
        assertThat(mv(r.undone())).containsExactly(move(1, 2, "30"));
        assertThat(b.out(1)).isEqualByComparingTo("20");
        assertThat(b.transit()).isEqualByComparingTo("530");

        b.post(2, "530");
        assertThat(b.transit()).isZero();
        assertThat(b.arrived(1)).isEqualByComparingTo("1000");
        assertThat(b.arrived(2)).isEqualByComparingTo("1000");
    }

    /** §8-2：反审核单 1 那张 1050 的入库单，冲出去的 50 全退回来，单 2 未入库回到 550；冲抵记录删掉 */
    @Test
    void unauditOverReceipt_undoesOffset() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);
        b.post(1, "1050");
        b.post(2, "450");

        Result r = b.post(1, "-1050");

        assertThat(mv(r.undone())).containsExactly(move(1, 2, "50"));
        assertThat(r.updated()).containsValue(BigDecimal.ZERO);
        assertThat(b.offsets).isEmpty();
        assertThat(b.pending(2)).isEqualByComparingTo("550");
    }

    /** §8-3：被冲的单已结案，冲出的那张退货了照样退回（状态由调用方决定不动）；结案的单不再接新的冲抵 */
    @Test
    void closedTarget_undoStillApplies_butGetsNoNewOffset() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);
        b.post(1, "1050");
        b.close(2);

        assertThat(mv(b.post(1, "-20").undone())).containsExactly(move(1, 2, "20"));
        assertThat(b.post(1, "20").added()).isEmpty();
        assertThat(b.out(1)).isEqualByComparingTo("30");
    }

    /**
     * 决定 1：靠冲入到齐的单，供应商照单再送来的照样入；入了以后先把冲来的还给多收的那一行。
     * 还回去的那 50 只冲给多收那一刻以前就审核了的单：CG4 是以前审核的、还开着，冲给它；CG3 是以后才审核的，不冲
     */
    @Test
    void giveBack_thenRespreadOnlyToOrdersAuditedBeforeTheOverReceipt() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);
        b.post(1, "1050");
        b.line(4, "CG4", "11-20", T0.minusDays(1));
        b.line(3, "CG3", "11-01", T0.plusDays(5));
        b.post(2, "950");
        assertThat(b.pending(2)).isZero();

        Result r = b.post(2, "50");

        assertThat(mv(r.undone())).containsExactly(move(1, 2, "50"));
        assertThat(mv(r.added())).containsExactly(move(1, 4, "50"));
        assertThat(b.in(2)).isZero();
        assertThat(b.in(3)).isZero();
        assertThat(b.in(4)).isEqualByComparingTo("50");
    }

    /**
     * 决定 3 的延续：还回来再冲的那笔记的还是原来多收那一刻（T0），不是还回的时候。
     * 冲了两跳以后再还回，T0 以后才审核的 CG5 照样不冲
     */
    @Test
    void respreadKeepsOriginalOverReceiptTime_acrossTwoHops() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);
        b.post(1, "1050");
        b.line(4, "CG4", "11-20", T0.minusDays(1));
        b.line(5, "CG5", "11-01", T0.plusDays(5));
        b.clock = T0.plusDays(6);
        b.post(2, "1000");
        assertThat(b.in(4)).isEqualByComparingTo("50");
        assertThat(b.offsets).filteredOn(o -> o.toId() == 4).singleElement().satisfies(o -> assertThat(o.createTime()).isEqualTo(T0));

        Result r = b.post(4, "1000");

        assertThat(mv(r.undone())).containsExactly(move(1, 4, "50"));
        assertThat(r.added()).isEmpty();
        assertThat(b.in(5)).isZero();
        assertThat(b.out(1)).isZero();
    }

    /**
     * 有时间限制的（还回来的）先冲、这次入的后冲（复查 #5）：单 1 原来多收的 50（T1）只能去 T1 以前审核的单 3；
     * 单 2 这次多收的 50 哪都能去，冲给 T2 才审核的单 4。反过来先冲这次入的，会把单 3 占掉，单 1 的 50 就冲不出去了
     */
    @Test
    void timeLimitedSurplusSpreadsBeforeFreshArrival() {
        Book b = new Book();
        b.line(1, "CG1", "11-01", T0);
        b.line(2, "CG2", "11-02", T0);
        b.line(3, "CG3", "11-10", T0, "50");
        b.clock = T0.plusHours(1);
        b.post(1, "1050");
        assertThat(b.in(2)).isEqualByComparingTo("50");
        b.line(4, "CG4", "11-12", T0.plusHours(2));
        b.clock = T0.plusHours(3);

        Result r = b.post(2, "1050");

        assertThat(mv(r.undone())).containsExactly(move(1, 2, "50"));
        assertThat(mv(r.added())).containsExactly(move(1, 3, "50"), move(2, 4, "50"));
        assertThat(b.transit()).isEqualByComparingTo("950");
    }

    /** 一张入库单入了同一张采购单的两行（同一组）：第 1 行多收的先冲给同单第 2 行（同一天同单号按行号），第 2 行有了冲入就不再往外冲 */
    @Test
    void onePostingTwoLinesOfSameOrder() {
        LocalDate day = LocalDate.parse("2026-11-10");
        Line a = new Line(1, 1, "CG1", "G", day, 1, T0, true, q("1000"), q("1050"), q("0"), q("0"));
        Line bLine = new Line(2, 1, "CG1", "G", day, 2, T0, true, q("1000"), q("500"), q("0"), q("0"));
        Line c = new Line(3, 3, "CG2", "G", day, 1, T0, true, q("1000"), q("0"), q("0"), q("0"));

        Result r = OverOffsetPlan.plan(List.of(a, bLine, c), List.of(), Map.of(1L, q("1050"), 2L, q("500")));

        assertThat(mv(r.added())).containsExactly(move(1, 2, "50"));
        assertThat(r.inDelta()).containsOnlyKeys(2L);
    }

    /** 决定 2：有冲入的行自己又多收了：先把冲入的还回去，剩下多收的再冲给别的单。一行不会同时有冲入和冲出 */
    @Test
    void lineWithOffsetIn_overReceivesItself_givesBackFirst() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.line(2, "CG2", "11-11", T0);
        b.line(3, "CG3", "11-12", T0);
        b.post(1, "1050");

        Result r = b.post(2, "1040");

        assertThat(mv(r.undone())).containsExactly(move(1, 2, "50"));
        assertThat(mv(r.added())).containsExactlyInAnyOrder(move(2, 3, "40"), move(1, 3, "50"));
        assertThat(b.in(2)).isZero();
        assertThat(b.out(2)).isEqualByComparingTo("40");
        assertThat(b.in(3)).isEqualByComparingTo("90");
    }

    /**
     * 决定 3：老单多收、当时没单可冲，留着的 50 不冲给以后审核的单（§1）。这张老单以后再有过账，也只冲这次新到的那部分：
     * 退货 5 什么都不冲；反审核这张退货单，回来的 5 是现在才到的，冲给开着的单
     */
    @Test
    void oldLeftover_notSpreadToLaterOrders_onlyFreshArrival() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.post(1, "1050");
        b.line(3, "CG3", "11-12", T0.plusDays(5));

        assertThat(b.post(1, "-5").isEmpty()).isTrue();
        Result r = b.post(1, "5");

        assertThat(mv(r.added())).containsExactly(move(1, 3, "5"));
        assertThat(b.out(1)).isEqualByComparingTo("5");
    }

    /** §8-4：不同组的（供应商、主体、采购单位不同都拼进组键）、已经冲出过的不冲 */
    @Test
    void otherGroupOrLineWithOffsetOut_notOffset() {
        Book b = new Book();
        b.line(1, "CG1", "11-10", T0);
        b.lines.put(2L, new Line(2, 2, "CG2", "OTHER", LocalDate.parse("2026-11-01"), 1, T0, true, q("1000"), q("0"), q("0"), q("0")));
        b.lines.put(3L, new Line(3, 3, "CG3", "G", LocalDate.parse("2026-11-01"), 1, T0, true, q("1000"), q("900"), q("0"), q("10")));
        b.line(4, "CG4", "11-20", T0);

        assertThat(mv(b.post(1, "1050").added())).containsExactly(move(1, 4, "50"));
    }

    /** §8-7：三张没到齐的，单据日期 11-10、11-12、11-12：先冲 11-10，再按单号冲 11-12 的两张 */
    @Test
    void order_byOrderDateThenNo() {
        Book b = new Book();
        b.line(1, "CG0", "11-01", T0);
        b.line(2, "CG9", "11-12", T0, "100");
        b.line(3, "CG5", "11-12", T0, "100");
        b.line(4, "CG7", "11-10", T0, "100");

        Result r = b.post(1, "1250");

        assertThat(mv(r.added())).containsExactly(move(1, 4, "100"), move(1, 3, "100"), move(1, 2, "50"));
    }

    /** 冲给了两张单，退货时从最近冲的那笔往前退 */
    @Test
    void undo_mostRecentFirst() {
        Book b = new Book();
        b.line(1, "CG1", "11-01", T0);
        b.line(2, "CG2", "11-10", T0, "30");
        b.post(1, "1030");
        b.line(3, "CG3", "11-11", T0, "100");
        b.clock = T0.plusHours(1);
        b.post(1, "20");
        assertThat(b.in(2)).isEqualByComparingTo("30");
        assertThat(b.in(3)).isEqualByComparingTo("20");

        Result r = b.post(1, "-25");

        assertThat(mv(r.undone())).containsExactly(move(1, 3, "20"), move(1, 2, "5"));
    }

    // ---------- 小账本 ----------

    private static BigDecimal q(String v) {
        return new BigDecimal(v);
    }

    /** "from>to:数"，比数不比多收时间（时间另有 respreadKeepsOriginalOverReceiptTime 查） */
    private static String move(long from, long to, String qty) {
        return from + ">" + to + ":" + q(qty).stripTrailingZeros().toPlainString();
    }

    private static List<String> mv(List<Move> moves) {
        return moves.stream().map(m -> m.fromId() + ">" + m.toId() + ":" + m.qty().stripTrailingZeros().toPlainString()).toList();
    }

    /** 同组（组键 G）的采购行和冲抵；post 照 PurchaseOffsetBook 写回 */
    private static final class Book {
        final Map<Long, Line> lines = new LinkedHashMap<>();
        final List<Offset> offsets = new ArrayList<>();
        long nextId = 100;
        OffsetDateTime clock = T0;

        void line(long id, String no, String date, OffsetDateTime audit) {
            line(id, no, date, audit, "1000");
        }

        void line(long id, String no, String date, OffsetDateTime audit, String qty) {
            lines.put(id, new Line(id, id, no, "G", LocalDate.parse("2026-" + date), 1, audit, true, q(qty), q("0"), q("0"), q("0")));
        }

        void close(long id) {
            Line l = lines.get(id);
            lines.put(id, new Line(l.id(), l.poId(), l.poNo(), l.group(), l.orderDate(), l.seq(), l.auditTime(), false, l.qty(), l.net(),
                    l.in(), l.out()));
        }

        Result post(long id, String delta) {
            Line l = lines.get(id);
            lines.put(id, new Line(l.id(), l.poId(), l.poNo(), l.group(), l.orderDate(), l.seq(), l.auditTime(), l.open(), l.qty(),
                    l.net().add(q(delta)), l.in(), l.out()));
            List<Offset> mine = offsets.stream().filter(o -> o.fromId() == id || o.toId() == id).toList();
            Result r = OverOffsetPlan.plan(lines.values(), mine, Map.of(id, q(delta)));
            r.updated().forEach((oid, qty) -> {
                offsets.removeIf(o -> o.id() == oid);
                if (qty.signum() > 0) {
                    Offset old = mine.stream().filter(o -> o.id() == oid).findFirst().orElseThrow();
                    offsets.add(new Offset(oid, old.fromId(), old.toId(), qty, old.createTime()));
                }
            });
            r.added().forEach(m -> offsets.add(new Offset(nextId++, m.fromId(), m.toId(), m.qty(), m.since() != null ? m.since() : clock)));
            r.touchedLines().forEach(lid -> {
                Line x = lines.get(lid);
                lines.put(lid, new Line(x.id(), x.poId(), x.poNo(), x.group(), x.orderDate(), x.seq(), x.auditTime(), x.open(), x.qty(),
                        x.net(), x.in().add(r.inDelta().getOrDefault(lid, BigDecimal.ZERO)),
                        x.out().add(r.outDelta().getOrDefault(lid, BigDecimal.ZERO))));
            });
            lines.values().forEach(x -> assertThat(x.in().signum() > 0 && x.out().signum() > 0).as("行 %s 同时有冲入和冲出", x.id()).isFalse());
            clock = clock.plusMinutes(1);
            return r;
        }

        BigDecimal in(long id) {
            return lines.get(id).in();
        }

        BigDecimal out(long id) {
            return lines.get(id).out();
        }

        BigDecimal arrived(long id) {
            Line l = lines.get(id);
            return l.net().add(l.in()).subtract(l.out());
        }

        BigDecimal pending(long id) {
            return lines.get(id).qty().subtract(arrived(id)).max(BigDecimal.ZERO);
        }

        BigDecimal transit() {
            return lines.keySet().stream().filter(id -> lines.get(id).open()).map(this::pending).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
