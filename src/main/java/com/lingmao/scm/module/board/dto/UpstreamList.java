package com.lingmao.scm.module.board.dto;

import java.util.List;

/** GET /api/board/upstream：按最新一条提醒的时间倒序，不分页；canSeeAll 同待办 */
public record UpstreamList(boolean canSeeAll, List<UpstreamRow> rows) {
}
