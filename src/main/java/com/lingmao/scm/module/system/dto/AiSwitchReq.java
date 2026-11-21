package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotNull;

/** 打开或关闭 AI 功能：enabled 必填 */
public record AiSwitchReq(@NotNull(message = "要说清楚是打开还是关闭") Boolean enabled) {
}
