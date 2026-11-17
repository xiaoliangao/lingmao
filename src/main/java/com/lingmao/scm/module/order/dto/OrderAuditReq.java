package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 审核（前端 OrderAuditReq）：每一级都填承诺交期，以最后一级的为准 */
public record OrderAuditReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                            @NotNull(message = "请填承诺交期") LocalDate commitDate) {
}
