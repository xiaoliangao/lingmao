package com.lingmao.scm.module.purchase.dto;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

/** 表单的默认值（前端 PurchaseDefaults）。不带采购员：采购员就是下采购单的人，不跟供应商的负责采购员走（用户 11-22 定） */
public record PurchaseDefaultsVO(String settleMethod, BigDecimal taxPct, LocalDate deliveryDate,
                                 List<PurchaseLineDefault> lines) {
}
