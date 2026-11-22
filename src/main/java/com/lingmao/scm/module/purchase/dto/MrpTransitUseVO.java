package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 本次使用在途用了哪条采购行（前端 MrpTransitUse）：qty、arrivedQty 是库存单位，arrivedQty 是现算的；
 * canOpen 是我打不打得开那张采购单（别的采购建的备货单"本人"范围打不开，页面只显示单号）
 */
public record MrpTransitUseVO(long purchaseItemId, long purchaseOrderId, String purchaseNo, String purchaseType, LocalDate eta,
                              BigDecimal qty, BigDecimal arrivedQty, boolean canOpen) {
}
