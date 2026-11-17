package com.lingmao.scm.module.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/production-orders/schedule-quote（前端 ScheduleQuote）：一家工厂做这个款，每种加工方式的现价、结算方式、税率。
 * usable = false 时 reason 写原因；defaultMode 按字典顺序取档案里有现价的第一种，都没有是字典第一项
 */
public record ScheduleQuoteVO(long factoryId, String factoryName, boolean usable, String reason, String settleMethod, BigDecimal taxPct,
                              List<ModePrice> prices, String defaultMode) {

    /** 一种加工方式的现价，元 / 件；没有是 null */
    public record ModePrice(String processMode, BigDecimal price, Long priceSourceId, LocalDate effectiveDate) {
    }
}
