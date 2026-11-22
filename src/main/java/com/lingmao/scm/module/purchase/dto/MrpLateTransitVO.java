package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 赶不上的在途（前端 MrpLateTransit）：算料时的快照，freeQty 是当时还能用的数（库存单位）；canOpen 同 MrpTransitUseVO */
public record MrpLateTransitVO(long purchaseItemId, long purchaseOrderId, String purchaseNo, String purchaseType, LocalDate eta,
                               BigDecimal freeQty, boolean canOpen) {
}
