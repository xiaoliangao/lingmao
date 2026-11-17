package com.lingmao.scm.module.purchase.dto;

import java.util.List;

/** 一张需求订单的齐料状态（前端 OrderKitting，PRD §6.6.3） */
public record OrderKittingVO(long orderId, String status, int readyCount, int totalCount, String label, List<Long> calcIds) {
}
