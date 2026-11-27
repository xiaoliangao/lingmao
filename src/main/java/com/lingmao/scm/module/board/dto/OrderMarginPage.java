package com.lingmao.scm.module.board.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单毛利：这一页的单，和筛出来的全部单的合计。合计只算人民币的单（外币单没有汇率，PRD §7 币种）。
 *
 * @param costMissingOrders 有件数算不了成本的单几张
 * @param foreignOrders     外币单几张（不算毛利、不进合计）
 */
public record OrderMarginPage(List<OrderMarginRow> list, long total, BigDecimal salesAmount, BigDecimal costAmount, BigDecimal margin,
                              BigDecimal marginRate, long costMissingOrders, long foreignOrders) {
}
