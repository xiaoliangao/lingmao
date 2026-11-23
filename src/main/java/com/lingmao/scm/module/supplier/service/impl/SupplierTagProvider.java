package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import org.springframework.stereotype.Component;

@Component
public class SupplierTagProvider implements TagObjectProvider {
    private final SupplierMapper mapper;
    public SupplierTagProvider(SupplierMapper mapper) { this.mapper = mapper; }
    @Override public TagObjectType type() { return TagObjectType.SUPPLIER; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "supplier:supplier:list", "supplier:supplier:edit");
        Supplier row = TagObjectProvider.present(access.locks() ? mapper.selectByIdForUpdate(id) : mapper.selectById(id));
        if (access.locks()) SupplierRules.ensureEditable(row.getStatus());
        return TagObjectProvider.target(type(), id, null);
    }
}
