package com.lingmao.scm.module.board.service.impl;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 首页条目第二行的文字：日期"11-25"、数量"2,400"，空的段不拼（docs/api/06 §2.3 的例子） */
final class BoardTexts {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private BoardTexts() {
    }

    /** "11-25"；null 是 "—" */
    static String md(LocalDate date) {
        return date == null ? "—" : date.format(MONTH_DAY);
    }

    /** 东八区的那一天 "11-25" */
    static String md(OffsetDateTime time) {
        return time == null ? "—" : time.atZoneSameInstant(SHANGHAI).toLocalDate().format(MONTH_DAY);
    }

    /** 件数 "2,400" */
    static String count(long n) {
        return String.format("%,d", n);
    }

    /** 物料数量 "1,234.5"：最多 3 位小数，末尾的 0 不要 */
    static String qty(BigDecimal n) {
        return n == null ? "—" : new DecimalFormat("#,##0.###").format(n);
    }

    /** 用 " · " 连起来，空的段跳过 */
    static String dot(String... parts) {
        return Arrays.stream(parts).filter(Objects::nonNull).filter(p -> !p.isBlank()).collect(Collectors.joining(" · "));
    }

    /** 款号几个：前两个，多的写"等 n 个款" */
    static String codes(List<String> codes) {
        if (codes.size() <= 2) {
            return String.join("、", codes);
        }
        return String.join("、", codes.subList(0, 2)) + " 等 " + codes.size() + " 个款";
    }
}
