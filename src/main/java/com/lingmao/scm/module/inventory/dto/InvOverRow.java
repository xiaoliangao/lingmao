package com.lingmao.scm.module.inventory.dto;

import java.math.BigDecimal;

/**
 * 1004 超出上限的一行（docs/api/04 §3.4.2，前端 InvOverRow）：采购入库超收、采购退货超过可退、退料超过已领。
 * 源单那边（StockSourceHandler）查出来抛，名称、单位是翻好的中文
 *
 * @param reason 上限怎么来的，如"采购 871 米，溢收 5%，已入库 500、已退 0"
 */
public record InvOverRow(int seq, String skuCode, String materialName, String sourceNo,
                         BigDecimal thisQty, BigDecimal maxQty, String unit, String reason) {
}
