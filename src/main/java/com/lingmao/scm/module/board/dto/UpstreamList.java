package com.lingmao.scm.module.board.dto;
import java.util.List;
/** 授权范围内的提醒分组；游标翻页，总数与当前页独立。 */
public record UpstreamList(boolean canSeeAll, List<UpstreamRow> rows, String nextCursor, boolean hasMore, long total) { }
