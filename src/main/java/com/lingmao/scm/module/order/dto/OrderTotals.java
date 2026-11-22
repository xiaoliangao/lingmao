package com.lingmao.scm.module.order.dto;

/** 需求订单的张数、件数合计（首页概况的在手订单，docs/api/06 §2.2） */
public record OrderTotals(long count, long qty) {
}
