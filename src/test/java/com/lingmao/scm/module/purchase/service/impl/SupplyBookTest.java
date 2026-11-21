package com.lingmao.scm.module.purchase.service.impl;

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

    /** 多收、冲出去的那一行：冲出去的不算它的已到，它自己的在途是 0（多收不会变成负的在途） */
    @Test
    void offsetOut_notCountedAsReceivedOfThatLine() {
        SupplyBook.Line po1 = new SupplyBook.Line(1L, 10L, "CG1", "ORDER", SKU, 1L, new BigDecimal("1000"),
                new BigDecimal("1000"), BigDecimal.ONE, null, true, true, 7L, 70L, true);

        assertThat(po1.receivedStock()).isEqualByComparingTo("1000");
        assertThat(po1.remainingStock()).isZero();
        assertThat(po1.pendingStock()).isZero();
    }
}
