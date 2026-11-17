package com.lingmao.scm.module.production.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 生产这边拼提示、操作记录用的小工具 */
final class ProdTexts {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private ProdTexts() {
    }

    /** 数字去掉末尾的 0：38.0000 → 38 */
    static String plain(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
    }

    /** 单价、档案价按两位显示：38 → 38.00 */
    static String money(BigDecimal n) {
        return n == null ? "" : n.setScale(Math.max(2, n.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY).toPlainString();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** 11-20 */
    static String md(LocalDate d) {
        return d == null ? "" : d.format(MONTH_DAY);
    }
}
