package com.lingmao.scm.module.board.dto;

import java.util.List;

/**
 * 待办的一组（docs/api/06 §2.3）：key 是 AUDIT / PRODUCTION_LATE / CUT_RISK / ARRIVAL_LATE / UPSTREAM / UNSCHEDULED / ISSUE / CERT。
 * items 最多 20 条，total 是一共几条
 */
public record TodoGroup(String key, long total, List<TodoItem> items) {
}
