package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.security.DataScope;
import org.springframework.stereotype.Component;

@Component
public class FgDocTagProvider implements TagObjectProvider {
    private final FgDocMapper mapper;
    public FgDocTagProvider(FgDocMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.FG_DOC; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "fg:doc:list", "fg:doc:edit");
        FgDoc row = TagObjectProvider.present(access.locks() ? mapper.lockById(id) : mapper.selectById(id));
        DataScope.current(false).check(row.getCreateBy());
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == DocStatus.DRAFT);
        return target;
    }
}
