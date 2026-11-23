package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 标签按稳定 ID 取交集：同时包含全部所选标签；空列表不筛，原始最多 30 项。 */
public class TaggedPageQuery extends PageQuery {
    @NotNull(message = "标签筛选列表不能为空")
    @Size(max = 30, message = "一次最多按 30 个标签筛选")
    private List<@NotNull(message = "标签 ID 不能为空") @Positive(message = "标签 ID 必须为正数") Long> tagIds = List.of();
    private TagFilter tagFilter;

    public List<Long> getTagIds() { return tagIds; }
    public void setTagIds(List<Long> ids) { tagIds = ids; tagFilter = null; }

    /** 只读属性：没有 JavaBean setter，客户端不能绑定内部 tenant/ids。 */
    @JsonIgnore
    public TagFilter getTagFilter() {
        if (tagIds != null && !tagIds.isEmpty() && tagFilter == null) {
            throw new IllegalStateException("Tag filters must be authorized before querying");
        }
        return tagFilter;
    }

    public void useVerifiedTagFilter(TagFilter filter) { tagFilter = filter; }
}
