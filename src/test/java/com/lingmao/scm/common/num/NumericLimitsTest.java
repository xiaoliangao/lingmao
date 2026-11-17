package com.lingmao.scm.common.num;

import com.lingmao.scm.common.exception.BizException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class NumericLimitsTest {
    @Test void exactCapAndTrailingZerosAreAccepted() {
        assertThat(NumericLimits.price(new BigDecimal("9999999999.999900"), "第1行")).isEqualByComparingTo(NumericLimits.MAX_PRICE);
        assertThat(NumericLimits.amount(NumericLimits.MAX_AMOUNT, "合计")).isEqualTo(NumericLimits.MAX_AMOUNT);
        assertThat(NumericLimits.pieces(Integer.MAX_VALUE, "订单")).isEqualTo(Integer.MAX_VALUE);
    }
    @Test void overflowAndExcessPrecisionFailAs400WithLocation() {
        for (Runnable r : new Runnable[]{
                () -> NumericLimits.price(new BigDecimal("10000000000"), "第2行："),
                () -> NumericLimits.price(new BigDecimal("0.00001"), "第2行："),
                () -> NumericLimits.amount(new BigDecimal("1000000000000"), "第2行："),
                () -> NumericLimits.pieces(2147483648L, "第2行：")}) {
            assertThatThrownBy(r::run).isInstanceOf(BizException.class).hasMessageContaining("第2行")
                    .extracting("code").isEqualTo(400);
        }
    }
}
