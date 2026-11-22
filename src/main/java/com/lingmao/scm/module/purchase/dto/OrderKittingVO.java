package com.lingmao.scm.module.purchase.dto;

import java.util.List;

/**
 * 一张需求订单的齐料状态（前端 OrderKitting，PRD §6.6.3）。状态按这张订单所有的算料单算；
 * calcIds 只列我打得开的算料单（新的在前），"本人"范围的跟单打不开采购建的，是空的
 */
public record OrderKittingVO(long orderId, String status, int readyCount, int totalCount, String label, List<Long> calcIds) {
}
