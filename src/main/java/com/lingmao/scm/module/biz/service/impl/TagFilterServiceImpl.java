package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.core.TagName;
import com.lingmao.scm.module.biz.dto.TagFilter;
import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import com.lingmao.scm.module.biz.dto.TagQuery;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.biz.service.TagFilterService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class TagFilterServiceImpl implements TagFilterService {
    private final TagRepository repository;
    private final DeploymentNamespace namespace;
    private final TagCache cache;

    @Override public void prepare(TaggedPageQuery query) {
        List<Long> raw = query.getTagIds();
        if (raw == null || raw.size() > 30 || raw.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BizException(400, "一次最多按 30 个有效标签 ID 筛选");
        }
        query.useVerifiedTagFilter(null);
        if (raw.isEmpty()) return;
        if (LoginUser.currentUserId() == null) throw new BizException(401, "请先登录");
        if (!LoginUser.hasAuthority("biz:tag:list")) throw new BizException(403, "没有查看公共标签的权限");
        List<Long> ids = raw.stream().distinct().sorted().toList();
        // 只验证最多 30 个标签，不读取匹配对象集合；归档标签仍指向自己的历史关联。
        repository.findByIds(ids);
        query.useVerifiedTagFilter(new TagFilter(namespace.tenant(), ids));
    }

    @Override public List<TagVO> options(TagQuery query) {
        if (LoginUser.currentUserId() == null) throw new BizException(401, "请先登录");
        if (!LoginUser.hasAuthority("biz:tag:list")) throw new BizException(403, "没有查看公共标签的权限");
        if (query == null || query.getSize() == null || query.getSize() < 1 || query.getSize() > 100
                || query.getIncludeIds() == null || query.getIncludeIds().size() > 100
                || query.getIncludeIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BizException(400, "一次最多查询 100 个有效标签");
        }
        String canonical = TagName.prefix(query.getKeyword());
        var ids = query.getIncludeIds().stream().distinct().toList();
        int budget = Math.max(query.getSize(), ids.size());
        return cache.filterOptions(canonical, budget, ids, () -> {
            var tags = new ArrayList<>(repository.findByIds(ids));
            int remaining = budget - tags.size();
            if (remaining > 0) tags.addAll(repository.searchForFilter(query.getKeyword(), remaining, ids));
            return tags.stream().map(tag -> new TagVO(tag.id(), tag.name(), tag.archived(), tag.version())).toList();
        });
    }
}
