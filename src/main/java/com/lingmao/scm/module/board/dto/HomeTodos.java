package com.lingmao.scm.module.board.dto;

import java.util.List;

/**
 * GET /api/board/home/todos。
 *
 * @param groups    有条目的组，按 AUDIT、PRODUCTION_LATE…… 的顺序
 * @param counts    "其他提醒"，数是 0 的、没权限的不在里面
 * @param canSeeAll 数据范围是"全部"（不是看审核权限）：true 时页面出"全部 / 我的"和负责人筛选
 */
public record HomeTodos(List<TodoGroup> groups, List<TodoCount> counts, boolean canSeeAll) {
}
