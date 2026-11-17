package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 本次使用在途用了哪条采购行（前端 MrpTransitUse）：qty、arrivedQty 是库存单位，arrivedQty 是现算的 */
public record MrpTransitUseVO(long purchaseItemId, long purchaseOrderId, String purchaseNo, String purchaseType, LocalDate eta,
                              BigDecimal qty, BigDecimal arrivedQty) {
}
