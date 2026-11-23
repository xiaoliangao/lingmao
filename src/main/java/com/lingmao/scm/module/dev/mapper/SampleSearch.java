package com.lingmao.scm.module.dev.mapper;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.Keywords;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** DAO 的有界筛选值，不是 HTTP DTO；Service 负责解析品类后传入真实子树 ID。 */
public record SampleSearch(String keyword, String showStatus, List<Long> categoryIds,
                           Integer year, String season, String brand, Long designerId,
                           Long factoryId, Long makerId, String nodeCode, boolean late,
                           LocalDate needFrom, LocalDate needTo, LocalDate createdFrom,
                           LocalDate createdTo, Sort sort) {
    public enum Sort { CREATED_DESC, CREATED_ASC, NEED_ASC, NEED_DESC, CODE_ASC, CODE_DESC }
    private static final Set<String> STATUSES = Set.of("WAIT_ASSIGN", "MAKING", "WAIT_REVIEW",
            "PASSED", "CONVERTED", "ELIMINATED");
    public SampleSearch {
        if (showStatus != null && !showStatus.isBlank() && !STATUSES.contains(showStatus)) {
            throw new BizException(400, "样衣状态不合法");
        }
        if (categoryIds != null) {
            if (categoryIds.size() > 5000 || categoryIds.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new BizException(400, "品类查询范围过大或编号不合法");
            }
            categoryIds = List.copyOf(categoryIds);
        }
        if (needFrom != null && needTo != null && needFrom.isAfter(needTo)
                || createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BizException(400, "日期区间起始不能晚于结束");
        }
        sort = sort == null ? Sort.CREATED_DESC : sort;
    }

    /** 一个过长单词不可能命中有界列，必须返回空集，不能把未生成 LIKE 参数误当无筛选。 */
    public boolean isImpossibleKeyword() {
        return keyword != null && keyword.codePointCount(0, keyword.length()) > 100
                && Keywords.codes(keyword) == null && Keywords.contains(keyword, 100) == null;
    }

    public static SampleSearch all() {
        return new SampleSearch(null, null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, Sort.CREATED_DESC);
    }
}
