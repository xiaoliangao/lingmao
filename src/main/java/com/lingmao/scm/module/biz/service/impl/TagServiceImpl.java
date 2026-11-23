package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.core.TagName;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.TagArchiveReq;
import com.lingmao.scm.module.biz.dto.TagObjectVO;
import com.lingmao.scm.module.biz.dto.TagQuery;
import com.lingmao.scm.module.biz.dto.TagRenameReq;
import com.lingmao.scm.module.biz.dto.TagReplaceReq;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.Tag;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.biz.service.TagService;
import com.lingmao.scm.security.LoginUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class TagServiceImpl implements TagService {
    private final TagRepository repository;
    private final TagCache cache;
    private final DocLogService logs;
    private final Map<TagObjectType, TagObjectProvider> providers;
    private final TransactionTemplate snapshotReads;

    public TagServiceImpl(TagRepository repository, TagCache cache, DocLogService logs, List<TagObjectProvider> providers,
                          PlatformTransactionManager transactions) {
        this.repository = repository; this.cache = cache; this.logs = logs;
        snapshotReads = new TransactionTemplate(transactions);
        snapshotReads.setReadOnly(true);
        snapshotReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotReads.setTimeout(5);
        var map = new EnumMap<TagObjectType, TagObjectProvider>(TagObjectType.class);
        for (TagObjectProvider provider : providers) {
            if (map.put(provider.type(), provider) != null) throw new IllegalStateException("duplicate tag object provider");
        }
        if (map.size() != TagObjectType.values().length) throw new IllegalStateException("missing tag object provider");
        this.providers = Map.copyOf(map);
    }

    @Override public List<TagVO> options(TagQuery query) {
        permission("biz:tag:list");
        if (query == null || query.getSize() == null || query.getSize() < 1 || query.getSize() > 100
                || query.getIncludeIds() == null || query.getIncludeIds().size() > 100) {
            throw new BizException(400, "一次最多查询 100 个标签");
        }
        var seen = new LinkedHashSet<Long>();
        for (Long id : query.getIncludeIds()) {
            if (id == null || id <= 0) throw new BizException(400, "标签 ID 不合法");
            seen.add(id);
        }
        List<Long> ids = List.copyOf(seen);
        int budget = Math.max(query.getSize(), ids.size());
        String canonical = TagName.prefix(query.getKeyword());
        return cache.options(canonical, budget, ids, () -> {
            List<TagVO> result = new ArrayList<>(repository.findByIds(ids).stream().map(TagServiceImpl::view).toList());
            int remaining = budget - result.size();
            if (remaining > 0) repository.searchActive(query.getKeyword(), remaining, ids).stream().map(TagServiceImpl::view).forEach(result::add);
            return List.copyOf(result);
        });
    }

    @Override public TagObjectVO read(TagObjectType type, long id) {
        if (type == TagObjectType.SAMPLE || type == TagObjectType.TECH_PACK) {
            // A newer cached payload cannot be combined with an older owner/SELF authorization.
            // Keep all packs on this path, including packs already adopted by a shared style.
            return snapshotReads.execute(status -> {
                TagObjectProvider.Target target = provider(type).resolve(id, TagObjectProvider.Access.READ);
                return view(repository.read(target(target)));
            });
        }
        TagObjectProvider.Target target = provider(type).resolve(id, TagObjectProvider.Access.READ);
        return cache.object(target, () -> view(repository.read(target(target))));
    }

    @Override public void checkReadable(TagObjectType type, long id) {
        provider(type).resolve(id, TagObjectProvider.Access.READ);
    }

    @Override @Transactional
    public TagObjectVO replace(TagObjectType type, long id, TagReplaceReq request) {
        TagObjectProvider.Target target = provider(type).resolve(id, TagObjectProvider.Access.WRITE);
        TagRepository.Change change = repository.replaceBindings(target(target), request.version(), request.names(), request.tagVersions(), request.retainIds());
        // 字段级完整前后值由同事务的数据库审计生成；操作时间轴保持 500 字符预算。
        logs.add(BizType.valueOf(type.name()), id, "replace-tags", "标签 " + change.before().links().size() + " → "
                + change.after().links().size() + " 个；版本 " + change.before().version() + " → " + change.after().version());
        cache.objectChanged(target, change.libraryChanged());
        return view(change.after());
    }

    @Override @Transactional
    public TagVO rename(long id, TagRenameReq request) {
        permission("biz:tag:manage");
        Tag before = repository.get(id);
        // The timeline must describe the same predecessor as the repository CAS.
        // A future version cannot borrow a concurrent rename that commits after this read.
        if (before.version() != request.version()) throw new BizException(1002, "标签已被修改，请刷新后重试");
        Tag after = repository.rename(id, request.version(), request.name());
        logs.add(BizType.PUBLIC_TAG, id, "rename", "标签名称「" + before.name() + "」→「" + after.name() + "」");
        cache.libraryChanged();
        return view(after);
    }

    @Override @Transactional
    public TagVO archive(long id, TagArchiveReq request) {
        permission("biz:tag:manage");
        Tag after = repository.archive(id, request.version());
        logs.add(BizType.PUBLIC_TAG, id, "archive", "归档标签「" + after.name() + "」，已有对象关联保留");
        cache.libraryChanged();
        return view(after);
    }

    @Override public PageResult<DocLogVO> logs(long id, PageQuery query) {
        permission("biz:tag:list");
        repository.get(id); // 当前可信 namespace 内真实存在，含归档；不开放 DocCommon 的附件或任意类型入口。
        if (query.getSize() > 100) throw new BizException(400, "一次最多查询 100 条标签操作记录");
        return logs.page(BizType.PUBLIC_TAG, id, query);
    }

    @Override public void authorizeReplaceReplay(TagObjectType type, long id) {
        provider(type).resolve(id, TagObjectProvider.Access.REPLAY);
    }

    @Override public void authorizeLibraryReplay(long id) {
        permission("biz:tag:manage");
        repository.get(id);
    }

    private TagObjectProvider provider(TagObjectType type) {
        TagObjectProvider provider = type == null ? null : providers.get(type);
        if (provider == null) throw new BizException(400, "不支持的标签对象类型");
        return provider;
    }
    private static void permission(String permission) {
        if (LoginUser.currentUserId() == null) throw new BizException(401, "请先登录");
        if (!LoginUser.hasAuthority(permission)) throw new BizException(403, "没有操作公共标签的权限");
    }
    private static TagRepository.Target target(TagObjectProvider.Target target) {
        return new TagRepository.Target(target.type(), target.id(), target.companyId());
    }
    private static TagVO view(Tag tag) { return new TagVO(tag.id(), tag.name(), tag.archived(), tag.version()); }
    private static TagObjectVO view(TagRepository.Snapshot snapshot) {
        return new TagObjectVO(snapshot.version(), snapshot.links().stream().map(link -> view(link.tag())).toList());
    }
}
