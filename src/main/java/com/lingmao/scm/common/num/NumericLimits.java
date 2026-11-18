package com.lingmao.scm.common.num;

import com.lingmao.scm.common.exception.BizException;

import java.math.BigDecimal;

/** 持久化容量检查，先给出可定位的400，不能等数据库NUMERIC溢出变500。 */
public final class NumericLimits {
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.9999");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    public static final BigDecimal MAX_QTY = new BigDecimal("99999999999.999");
    private NumericLimits() { }

    public static BigDecimal price(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) {
            throw new BizException(400, label + "单价超出范围（0–9999999999.9999）");
        }
        if (value.stripTrailingZeros().scale() > 4) throw new BizException(400, label + "单价最多四位小数");
        return Num.price(value);
    }

    public static BigDecimal amount(BigDecimal value, String label) {
        if (value != null && value.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new BizException(400, label + "金额超出上限999999999999.99");
        }
        return value;
    }

    public static BigDecimal quantity(BigDecimal value, String label) {
        if (value != null && value.abs().compareTo(MAX_QTY) > 0) {
            throw new BizException(400, label + "数量超出上限99999999999.999");
        }
        return value;
    }

    public static int pieces(long value, String label) {
        if (value < 0 || value > Integer.MAX_VALUE) throw new BizException(400, label + "总件数超出上限2147483647");
        return (int) value;
    }
}
