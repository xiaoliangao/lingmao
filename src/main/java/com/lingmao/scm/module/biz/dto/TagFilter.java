package com.lingmao.scm.module.biz.dto;

import java.util.List;

/** 服务端已验证的公共标签 AND 条件；不能从 HTTP 指定部署命名空间。 */
public record TagFilter(String tenant, List<Long> ids) {
    public TagFilter { ids = List.copyOf(ids); }
    public int size() { return ids.size(); }
}
