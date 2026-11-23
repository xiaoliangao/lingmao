package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import org.springframework.stereotype.Component;

/** 共享资料沿现有主档编辑规则；不凭登录者或请求头发明公司归属。 */
@Component
public class StyleTagProvider implements TagObjectProvider {
    private final StyleMapper mapper;
    public StyleTagProvider(StyleMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.STYLE; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "style:style:list", "style:style:edit");
        if (access.locks()) TagObjectProvider.present(mapper.lockById(id));
        TagObjectProvider.present(mapper.selectById(id));
        return TagObjectProvider.target(type(), id, null);
    }
}
