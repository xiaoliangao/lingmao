package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 算料公式（PRD §4.3 的例子、§6.6.2 验收 1、2）：只算，不碰库 */
class MrpCalculatorTest {

    private static final long SKU = 100L;
    private static final LocalDate NEED = LocalDate.of(2027, 1, 10);

    /** 卡其布：最小包装 1 米、起订量 100 米，参考单价 18 */
    private static final MaterialPurchaseInfo KHAKI = new MaterialPurchaseInfo(SKU, "ML0001-KQ", 50L, "FABRIC", "METER",
            BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), 7, null, null, 9L, null, null, new BigDecimal("18"), true);

    /** 五个尺码的件数 × 单件用量 = 962 米，损耗 3% */
    private static List<MrpCalculator.Demand> demands() {
        int[] qty = {80, 120, 120, 60, 20};
        String[] usage = {"2.25", "2.35", "2.45", "2.55", "2.65"};
        return java.util.stream.IntStream.range(0, qty.length).mapToObj(n -> new MrpCalculator.Demand(1L, "DD1", "27CF001", 1,
                "PURCHASE", 9L, 50L, SKU, qty[n], new BigDecimal(usage[n]), new BigDecimal("3"))).toList();
    }

    private static MrpCalculator.Row calculate(LocalDate eta) {
        SupplyBook.Line stockPo = new SupplyBook.Line(7L, 70L, "CG1", "STOCK", SKU, 1L, new BigDecimal("500"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, eta, true, true, null, null, false);
        SupplyBook book = new SupplyBook(Map.of(SKU, new BigDecimal("120")), List.of(stockPo), List.of(), List.of());
        MrpCalculator.Inputs in = new MrpCalculator.Inputs(Map.of(SKU, KHAKI), Map.of(), k -> null, NEED, Set.of(), null);
        List<MrpCalculator.Row> rows = MrpCalculator.calculate(demands(), book, in);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** 验收 1：物料仓 120、赶得上的备货在途 500 都扣，净需求 990.86 − 120 − 500 = 370.86，建议采购 371 */
    @Test
    void transitInTime_isUsed() {
        MrpCalculator.Row r = calculate(LocalDate.of(2026, 12, 20));
        assertThat(r.item().getGrossQty()).isEqualByComparingTo("990.86");
        assertThat(r.item().getUseStockQty()).isEqualByComparingTo("120");
        assertThat(r.item().getUseTransitQty()).isEqualByComparingTo("500");
        assertThat(r.item().getNetQty()).isEqualByComparingTo("370.86");
        assertThat(r.item().getSuggestQty()).isEqualByComparingTo("371");
        assertThat(r.item().getEstAmount()).isEqualByComparingTo("6678");
        assertThat(r.allocs()).singleElement().satisfies(a -> assertThat(a.qty()).isEqualByComparingTo("500"));
        assertThat(r.lates()).isEmpty();
    }

    /** 验收 2：备货单的预计到货晚于要料日期，500 列在赶不上的在途里、不扣，建议采购回到 871 */
    @Test
    void lateTransit_isListedNotUsed() {
        MrpCalculator.Row r = calculate(LocalDate.of(2027, 1, 12));
        assertThat(r.item().getUseTransitQty()).isEqualByComparingTo("0");
        assertThat(r.item().getLateTransitQty()).isEqualByComparingTo("500");
        assertThat(r.item().getNetQty()).isEqualByComparingTo("870.86");
        assertThat(r.item().getSuggestQty()).isEqualByComparingTo("871");
        assertThat(r.lates()).singleElement().satisfies(l -> assertThat(l.lineId()).isEqualTo(7L));
    }

    /** 备料比：物料上设的优先，没设按物料类型，都没有是 0（Q18、Q31） */
    @Test
    void reservePct_materialThenTypeThenZero() {
        assertThat(MrpCalculator.reservePct(KHAKI, Map.of("FABRIC", new BigDecimal("2")))).isEqualByComparingTo("2");
        assertThat(MrpCalculator.reservePct(KHAKI, Map.of())).isEqualByComparingTo("0");
        MaterialPurchaseInfo own = new MaterialPurchaseInfo(SKU, "ML0001-KQ", 50L, "FABRIC", "METER", BigDecimal.ONE, BigDecimal.ONE,
                null, null, null, null, null, new BigDecimal("5"), null, null, true);
        assertThat(MrpCalculator.reservePct(own, Map.of("FABRIC", new BigDecimal("2")))).isEqualByComparingTo("5");
    }
}
