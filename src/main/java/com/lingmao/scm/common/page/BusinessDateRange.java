package com.lingmao.scm.common.page;

import com.lingmao.scm.common.exception.BizException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** DATE 列直接按日期比较；TIMESTAMPTZ 统一使用东八区业务日的半开区间。 */
public final class BusinessDateRange {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDateRange() { }

    public static OffsetDateTime start(LocalDate date) {
        return date == null ? null : date.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime endExclusive(LocalDate date) {
        if (date == null) return null;
        try {
            return start(date.plusDays(1));
        } catch (java.time.DateTimeException e) {
            throw new BizException(400, "结束日期超出支持范围");
        }
    }

    public static void validate(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw new BizException(400, "开始日期不能晚于结束日期");
    }
}
