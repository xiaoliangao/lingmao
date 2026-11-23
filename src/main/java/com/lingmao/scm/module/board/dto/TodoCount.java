package com.lingmao.scm.module.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 只有 FG_PENDING 携带计数时的主体，防点击时跨主体导航。 */
public record TodoCount(String key, long count, @JsonInclude(JsonInclude.Include.NON_NULL) Long companyId) {
    public TodoCount(String key, long count) { this(key, count, null); }
}
