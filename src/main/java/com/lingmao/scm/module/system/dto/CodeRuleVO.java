package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一条编码规则（RFC 018 §4.7）。general 是通用规则（scopeValues 空，不能删）
 *
 * @param scopeValues 按什么分的字典值（大类、物料类型）
 */
public record CodeRuleVO(Long id, CodeTarget target, String name, boolean general, List<String> scopeValues,
                         List<CodeSegment> segments, Integer sort,
                         @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai") OffsetDateTime updateTime) {
}
