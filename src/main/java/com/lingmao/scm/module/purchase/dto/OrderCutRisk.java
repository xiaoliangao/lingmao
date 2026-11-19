package com.lingmao.scm.module.purchase.dto;

import java.time.LocalDate;

/**
 * 一张需求订单的料最晚的那一行晚于开裁（首页跟单看到的"晚于开裁"，docs/api/06 §2.3）：只给物料名和日期，不给采购单。
 *
 * @param arriveDate  预计到货 ?? 交期
 * @param needDate    要料日期（开裁）
 * @param cutLateDays 晚几天
 */
public record OrderCutRisk(String orderNo, long materialSkuId, String materialName, LocalDate arriveDate, LocalDate needDate,
                           long cutLateDays) {
}
