package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TagArchiveReq(
        @NotNull(message = "请提交标签版本") @Min(value = 0, message = "标签版本不能为负数") Integer version) { }
