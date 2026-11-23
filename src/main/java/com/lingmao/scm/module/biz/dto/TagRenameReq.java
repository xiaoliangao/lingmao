package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TagRenameReq(
        @NotNull(message = "请提交标签版本") @Min(value = 0, message = "标签版本不能为负数") Integer version,
        @NotNull(message = "请填写标签名称") @Size(max = 1024, message = "标签输入过长") String name) { }
