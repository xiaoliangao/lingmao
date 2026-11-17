package com.lingmao.scm.module.order.service.impl;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 订单域里几处共用的小工具：数字、日期写进提示语 */
final class OrderTexts {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private OrderTexts() {
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** 单价写进提示语：去掉末尾的 0（"89.5000" → "89.5"） */
    static String plain(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
    }

    /** 件数带千分位："1,200" */
    static String pieces(long n) {
        return new DecimalFormat("#,##0").format(n);
    }

    /** 两个日期一起出现时：同一年只写月日（"03-20 → 03-25"），跨年写全 */
    static String date(LocalDate d, LocalDate other) {
        if (d == null) {
            return "";
        }
        return other != null && other.getYear() == d.getYear() ? MONTH_DAY.format(d) : d.toString();
    }
}
