package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;

/** 物料类型的备料比（前端 ReservePct）：字典 material_type 的数值列 */
public record ReservePctVO(String materialType, String label, BigDecimal pct) {
}
