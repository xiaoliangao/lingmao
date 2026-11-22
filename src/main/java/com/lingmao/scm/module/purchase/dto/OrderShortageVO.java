package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;

/**
 * 审核对话框里缺的一种料（docs/api/02 §3.8）：数量都是库存单位。
 *
 * @param need      需求（含损耗、备料比）
 * @param available 可用：库存 + 赶得上的在途 − 别的算料单占着的
 * @param gap       缺口 = 需求 − 可用
 * @param leadDays  采购周期，天；物料没填是 null
 */
public record OrderShortageVO(long materialSkuId, BigDecimal need, BigDecimal available, BigDecimal gap, Integer leadDays) {
}
