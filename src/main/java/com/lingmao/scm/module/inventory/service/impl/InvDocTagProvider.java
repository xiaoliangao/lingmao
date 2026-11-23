package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.module.inventory.mapper.InvDocMapper;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.security.DataScope;
import org.springframework.stereotype.Component;

@Component
public class InvDocTagProvider implements TagObjectProvider {
    private final InvDocMapper mapper;
    public InvDocTagProvider(InvDocMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.INV_DOC; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "inventory:doc:list", "inventory:doc:edit");
        InvDoc row = TagObjectProvider.present(access.locks() ? mapper.lockById(id) : mapper.selectById(id));
        DataScope.current(false).check(row.getCreateBy());
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == DocStatus.DRAFT);
        return target;
    }
}
