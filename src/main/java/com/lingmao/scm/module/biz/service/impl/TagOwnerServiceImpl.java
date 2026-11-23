package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.biz.service.TagOwnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.Objects;

/** 与需要领域 Provider 的 TagService 分离，避免资料包/算料服务形成依赖环。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TagOwnerServiceImpl implements TagOwnerService {
    private final TagCache cache;

    @Override
    public void ownerChanged(TagObjectType type, long id, Long previousCompanyId, Long companyId) {
        var previous = new TagObjectProvider.Target(type, id, previousCompanyId);
        var current = new TagObjectProvider.Target(type, id, companyId);
        cache.objectChanged(previous, false);
        if (!Objects.equals(previousCompanyId, companyId)) cache.objectChanged(current, false);
    }

    @Override
    public void ownersChanged(TagObjectType type, Collection<Long> ids, Long previousCompanyId, Long companyId) {
        if (ids == null || ids.size() > 50_000) throw new BizException(400, "标签归属移交数量超过50000");
        // 在去重前校验原始预算；只核验可信服务传入的身份，不回查五万个包。
        for (Long id : ids) {
            if (id == null) throw new BizException(400, "标签对象编号不合法");
            new TagObjectProvider.Target(type, id, previousCompanyId);
            new TagObjectProvider.Target(type, id, companyId);
        }
        if (!ids.isEmpty()) cache.libraryChanged();
    }
}
