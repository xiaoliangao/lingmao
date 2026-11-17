package com.lingmao.scm.module.purchase.dto;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

/** 表单的默认值（前端 PurchaseDefaults） */
public record PurchaseDefaultsVO(String settleMethod, BigDecimal taxPct, Long buyerId, String buyerName, LocalDate deliveryDate,
                                 List<PurchaseLineDefault> lines) {
}
