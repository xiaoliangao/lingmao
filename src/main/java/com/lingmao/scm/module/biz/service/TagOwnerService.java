package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.entity.TagObjectType;
import java.util.Collection;

/** 内部归属移交通知；调用者已锁定真实父头，不读取或缓存对象授权。 */
public interface TagOwnerService {
    /** 当前事务提交后失效新旧主体身份，不改变标签内容版本。 */
    void ownerChanged(TagObjectType type, long id, Long previousCompanyId, Long companyId);
    /** 有界成组移交只推进一次库 epoch，不逐个加载对象或产生失效事件。 */
    void ownersChanged(TagObjectType type, Collection<Long> ids, Long previousCompanyId, Long companyId);
}
