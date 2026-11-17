package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReferenceServiceImpl implements ReferenceService {

    /**
     * 各域的实现，用到时才取（架构 §4.4 写的是注入 List）：单据的 Service 要调这里，它所在的域又实现了 ReferrerProvider，
     * 构造时就要齐的话，有人把 ReferrerProvider 写在单据 ServiceImpl 上就成了循环依赖，Spring Boot 默认起不来
     */
    private final ObjectProvider<ReferrerProvider> providers;
    private final UserService userService;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        List<DocRef> refs = providers.orderedStream()
                .flatMap(p -> p.referrers(type, id).stream())
                .sorted(Comparator.comparing(DocRef::type).thenComparingLong(DocRef::id))
                .toList();
        return withOwnerNames(refs);
    }

    @Override
    public void assertNoReferrer(BizType type, long id, String action) {
        List<DocRef> refs = referrers(type, id);
        if (!refs.isEmpty()) {
            throw new BizException(ErrorCodes.HAS_DOWNSTREAM, "已经有下游单据，不能" + action + "：先处理下面这些单据", refs);
        }
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocRef> found = new HashMap<>();
        providers.orderedStream().forEach(p -> found.putAll(p.describe(type, ids)));
        Map<Long, DocRef> result = new LinkedHashMap<>();
        List<DocRef> named = withOwnerNames(ids.stream().distinct()
                .map(id -> found.getOrDefault(id, placeholder(type, id)))
                .toList());
        named.forEach(ref -> result.put(ref.id(), ref));
        return result;
    }

    /** 那个域还没实现 describe（或者单据刚被删了）：只有类型和 id，页面照样能列出来、能点进去看 */
    private static DocRef placeholder(BizType type, long id) {
        return new DocRef(type, null, null, type.getLabel(), id, "", "", "", StatusTone.OFF, null, null);
    }

    /** 负责人的昵称一次取齐，不让每个域各查一遍 */
    private List<DocRef> withOwnerNames(List<DocRef> refs) {
        Set<Long> ownerIds = refs.stream().map(DocRef::ownerId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ownerIds.isEmpty()) {
            return refs;
        }
        Map<Long, String> names = userService.nicknames(ownerIds);
        return refs.stream()
                .map(r -> r.ownerId() == null ? r : r.withOwnerName(names.get(r.ownerId())))
                .toList();
    }
}
