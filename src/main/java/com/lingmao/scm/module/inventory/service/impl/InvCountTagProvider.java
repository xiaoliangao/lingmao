package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.inventory.entity.InvCount;
import com.lingmao.scm.module.inventory.mapper.InvCountMapper;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.security.DataScope;
import org.springframework.stereotype.Component;

@Component
public class InvCountTagProvider implements TagObjectProvider {
    private final InvCountMapper mapper;
    public InvCountTagProvider(InvCountMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.INV_COUNT; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "inventory:count:list", "inventory:count:edit");
        InvCount row = TagObjectProvider.present(access.locks() ? mapper.lockById(id) : mapper.selectById(id));
        DataScope.current(false).check(row.getCreateBy(), row.getCounterId());
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == DocStatus.DRAFT);
        return target;
    }
}
