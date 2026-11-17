package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单据状态动作的请求体（前端 types/biz.ts 的 DocActionReq，docs/api/README §4.2）：POST /api/xxx/{id}/audit 这种都收它。
 *
 * @param version 打开时拿到的版本号，对不上 → 1002
 * @param reason  驳回、作废这类要说原因的动作写一句，进操作记录；别的动作不用
 */
public record DocActionReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                           @Size(max = 255, message = "原因最多 255 个字") String reason) {
}
