package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * AI 功能统一开关（RFC 017 §3.7），管理后台 › AI 功能 显示它。
 * enabled：总开关；没有设置、读出错都是 false。updatedBy、updatedAt：上次谁、什么时候改的（昵称，没有昵称用账号），没改过是 null
 */
public record AiSwitchVO(boolean enabled,
                         String updatedBy,
                         @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
                         OffsetDateTime updatedAt) {
}
