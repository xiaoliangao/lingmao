package com.lingmao.scm.common.num;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 取整规则用 PRD 的例子钉住（PRD §4.3、§5.3、Q09） */
class NumTest {

    /** 规则：金额 2 位、单价 4 位、数量 3 位，都是四舍五入（0.5 进位，不是银行家舍入）；null 原样返回 */
    @Test
    void rounding_halfUp() {
        assertThat(Num.money(new BigDecimal("12.345"))).isEqualByComparingTo("12.35");
        assertThat(Num.money(new BigDecimal("12.344"))).isEqualByComparingTo("12.34");
        assertThat(Num.price(new BigDecimal("3.14165"))).isEqualByComparingTo("3.1417");
        assertThat(Num.qty(new BigDecimal("990.8605"))).isEqualByComparingTo("990.861");
        assertThat(Num.amount(new BigDecimal("3.333"), new BigDecimal("1.5"))).isEqualByComparingTo("5.00");
        assertThat(Num.amount(120, new BigDecimal("45.6789"))).isEqualByComparingTo("5481.47");
        assertThat(Num.amount((BigDecimal) null, BigDecimal.ONE)).isNull();
        assertThat(Num.money(null)).isNull();
    }

    /** 规则（PRD §4.3 的例子）：毛需求 962 米、损耗 3% → 990.86；净需求 870.86、最小包装 1、起订 100 → 建议采购 871 */
    @Test
    void mrpExample() {
        assertThat(Num.qty(Num.addPct(new BigDecimal("962"), new BigDecimal("3")))).isEqualByComparingTo("990.860");
        assertThat(Num.suggestPurchase(new BigDecimal("870.86"), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100")))
                .isEqualByComparingTo("871");
    }

    /** 规则：建议采购量向上取整到最小包装量的倍数、不小于起订量，换算系数的 6 位小数误差不多进一包；净需求 ≤ 0 是 0 */
    @Test
    void suggestPurchase_packMoqAndRatio() {
        assertThat(Num.suggestPurchase(BigDecimal.ONE, null, new BigDecimal("6"), new BigDecimal("10"))).isEqualByComparingTo("12");
        assertThat(Num.suggestPurchase(new BigDecimal("0.1"), null, new BigDecimal("0.3"), new BigDecimal("0.5"))).isEqualByComparingTo("0.6");
        assertThat(Num.suggestPurchase(BigDecimal.ZERO, null, new BigDecimal("6"), new BigDecimal("10"))).isEqualByComparingTo("0");
        assertThat(Num.suggestPurchase(new BigDecimal("101"), null, new BigDecimal("50"), null)).isEqualByComparingTo("150");
        assertThat(Num.suggestPurchase(new BigDecimal("30"), null, new BigDecimal("50"), new BigDecimal("200"))).isEqualByComparingTo("200");
        // 1 采购单位 = 0.333333 库存单位：1 ÷ 0.333333 = 3.000003，按 3 位取成 3.000，不进成 4
        assertThat(Num.suggestPurchase(BigDecimal.ONE, new BigDecimal("0.333333"), null, null)).isEqualByComparingTo("3");
        assertThat(Num.suggestPurchase(new BigDecimal("-5"), null, null, new BigDecimal("100"))).isEqualByComparingTo("0");
    }

    /** 规则（Q09）：上限向下取整——订单 120 件、溢装 3% 是 123（123.6 往下取）；物料 100.0005 × 1.05 取到 3 位往下 */
    @Test
    void caps_floor() {
        assertThat(Num.maxPieces(120, new BigDecimal("3"))).isEqualTo(123);
        assertThat(Num.maxPieces(120, null)).isEqualTo(120);
        assertThat(Num.maxPieces(Integer.MAX_VALUE, new BigDecimal("3"))).isEqualTo(Integer.MAX_VALUE);
        assertThat(Num.maxPieces(2_100_000_000, new BigDecimal("3"))).isEqualTo(Integer.MAX_VALUE);
        assertThat(Num.maxQty(new BigDecimal("100.0005"), new BigDecimal("5"))).isEqualByComparingTo("105.000");
        assertThat(Num.ratio(new BigDecimal("299"), new BigDecimal("100"))).isEqualByComparingTo("2.99");
        assertThat(Num.ratio(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }
}
