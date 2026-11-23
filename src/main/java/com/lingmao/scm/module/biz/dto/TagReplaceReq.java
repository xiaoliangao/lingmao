package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** names 是原始输入；既有名称回填用 # + displayName，保留一次规范化往返。 */
public record TagReplaceReq(
        @NotNull(message = "请提交对象标签版本") @Min(value = 0, message = "标签版本不能为负数") Integer version,
        @NotNull(message = "请提交标签名称列表") @Size(max = 30, message = "每个对象最多关联 30 个标签")
        List<@NotNull(message = "标签名称不能为空") @Size(max = 1024, message = "标签输入过长") String> names,
        @NotNull(message = "请提交当前标签版本") @Size(max = 30, message = "当前标签最多 30 个")
        Map<@Positive(message = "标签 ID 必须为正数") Long,
                @NotNull(message = "标签版本不能为空") @Min(value = 0, message = "标签版本不能为负数") Integer> tagVersions,
        @NotNull(message = "请明确保留的标签 ID") @Size(max = 30, message = "最多保留 30 个标签")
        List<@NotNull(message = "标签 ID 不能为空") @Positive(message = "标签 ID 必须为正数") Long> retainIds) { }
