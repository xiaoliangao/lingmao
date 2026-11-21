package com.lingmao.scm.module.production.dto;

/** 进度总览分段上的数（行数 = 订单 × 款） */
public record OrderProgressCounts(long total, long unscheduled, long near, long late, long production) {
}
