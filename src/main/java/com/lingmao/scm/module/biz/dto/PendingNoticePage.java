package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;
import java.time.OffsetDateTime;
import java.util.List;

/** 经过 SQL 授权、分页的提醒分组；总数独立查询，每单最多 100 条通知。 */
public record PendingNoticePage(List<Group> groups, long total, boolean hasMore) {
    public record Group(BizType docType, Long docId, OffsetDateTime latest, long noticesTotal,
                        List<DocNoticeVO> notices) { }
}
