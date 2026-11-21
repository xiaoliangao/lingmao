package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.purchase.mapper.SupplyMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 可用量账本里冲抵的口径（RFC 014 §5、§8-5）：采购行的已到、没到都按到货（净入库 + 冲入 − 冲出）算 */
class SupplyBookTest {

    private static final long SKU = 100L;

    /**
     * §8-5：已确认的算料单用了单 2 的 550 在途（当时单 2 采购 1000、净入库 450）。之后单 1 多收的 50 冲给单 2：
     * 单 2 到货 500、在途 500；这条使用里 50 算到了（占着库存），还没到的 500 照样占着单 2 的在途，单 2 没有能再给别人用的在途
     */
    @Test
    void offsetIn_countsAsArrivedForTheUse() {
        SupplyBook.Line po2 = new SupplyBook.Line(2L, 20L, "CG2", "STOCK", SKU, 1L, new BigDecimal("1000"),
                new BigDecimal("500"), BigDecimal.ONE, LocalDate.now().plusDays(5), true, true, null, null, false);
        SupplyBook.Use use = new SupplyBook.Use(1L, 9L, 90L, 2L, new BigDecimal("550"));
        SupplyBook book = new SupplyBook(Map.of(SKU, new BigDecimal("1500")), List.of(po2), List.of(use), List.of());

        assertThat(po2.pendingStock()).isEqualByComparingTo("500");
        assertThat(book.transitToMaterial(SKU)).isEqualByComparingTo("500");
        assertThat(book.arrivedOf(1L)).isEqualByComparingTo("50");
        assertThat(book.heldStock(SKU, Set.of())).isEqualByComparingTo("50");
        assertThat(book.heldTransit(po2, Set.of())).isEqualByComparingTo("500");
        assertThat(book.freeLines(SKU, Set.of(), null)).isEmpty();
    }

    /**
     * 取数（SupplyLoader.line）：多收 1050、冲出 50 的那一行，已到按 1000 算（冲出去的不算它的，算料单自己生成的这一行不多占 50）；
     * 冲入 50、净入库 450 的那一行到货 500。没冲出去的多收（1050）在途是 0，不会变成负的
     */
    @Test
    void loader_arrivedIncludesOffsets_andOverReceiptIsNeverNegativeTransit() {
        SupplyBook.Line out = SupplyLoader.line(row(1L, "1050", "0", "0", "50"));
        SupplyBook.Line in = SupplyLoader.line(row(2L, "450", "0", "50", "0"));
        SupplyBook.Line over = SupplyLoader.line(row(3L, "1080", "30", "0", "0"));

        assertThat(out.receivedStock()).isEqualByComparingTo("1000");
        assertThat(out.pendingStock()).isZero();
        assertThat(in.receivedStock()).isEqualByComparingTo("500");
        assertThat(in.pendingStock()).isEqualByComparingTo("500");
        assertThat(over.receivedStock()).isEqualByComparingTo("1050");
        assertThat(over.pendingStock()).isZero();
    }

    private static SupplyMapper.LineRow row(long id, String received, String returned, String offsetIn, String offsetOut) {
        return new SupplyMapper.LineRow(id, id * 10, "CG" + id, "ORDER", "RUNNING", SKU, 1L, "MATERIAL", new BigDecimal("1000"),
                new BigDecimal(received), new BigDecimal(returned), new BigDecimal(offsetIn), new BigDecimal(offsetOut), BigDecimal.ONE,
                null, 7L, 70L, "CONFIRMED");
    }
}
