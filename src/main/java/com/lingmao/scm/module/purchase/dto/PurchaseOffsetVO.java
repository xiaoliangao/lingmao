package com.lingmao.scm.module.purchase.dto;

import java.math.BigDecimal;

/**
 * 采购明细上的一笔冲抵（前端 PurchaseOffset，RFC 014）：这一行和别的采购行之间冲了多少，同一对行合成一条。数量按采购单位
 *
 * @param direction       IN 别的单多收、冲给这一行；OUT 这一行多收、冲给别的单
 * @param purchaseOrderId 对面那张采购单
 * @param purchaseNo      对面那张采购单的单号
 * @param warehouseName   对面那张采购单的交货仓库：不同仓库也冲，多收的料在哪个仓要看得到
 * @param qty             冲了多少，> 0
 */
public record PurchaseOffsetVO(String direction, Long purchaseOrderId, String purchaseNo, String warehouseName, BigDecimal qty) {
}
