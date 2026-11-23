package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import org.springframework.stereotype.Component;

/** 共享资料沿现有主档编辑规则；不凭登录者或请求头发明公司归属。 */
@Component
public class MaterialTagProvider implements TagObjectProvider {
    private final MaterialMapper mapper;
    public MaterialTagProvider(MaterialMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.MATERIAL; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "basic:material:list", "basic:material:edit");
        if (access.locks()) TagObjectProvider.present(mapper.lockById(id));
        TagObjectProvider.present(mapper.selectById(id));
        return TagObjectProvider.target(type(), id, null);
    }
}
