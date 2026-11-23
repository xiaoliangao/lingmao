package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class TagQuery {
    @Size(max = 1024, message = "标签搜索输入过长")
    private String keyword;
    @NotNull(message = "请指定查询条数")
    @Min(value = 1, message = "查询条数至少为 1")
    @Max(value = 100, message = "一次最多查询 100 个标签")
    private Integer size = 50;
    @NotNull(message = "标签 ID 列表不能为空")
    @Size(max = 100, message = "一次最多回显 100 个标签")
    private List<@NotNull(message = "标签 ID 不能为空") @Positive(message = "标签 ID 必须为正数") Long> includeIds = List.of();
}
