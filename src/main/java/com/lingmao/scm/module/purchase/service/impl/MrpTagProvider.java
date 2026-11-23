package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.purchase.service.MrpService;
import org.springframework.stereotype.Component;

@Component
public class MrpTagProvider implements TagObjectProvider {
    private final MrpCalcMapper mapper;
    private final MrpService service;
    public MrpTagProvider(MrpCalcMapper mapper, MrpService service) { this.mapper = mapper; this.service = service; }
    @Override public TagObjectType type() { return TagObjectType.MRP_CALC; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "purchase:mrp:list", "purchase:mrp:edit");
        MrpCalc row = TagObjectProvider.present(access.locks() ? mapper.lockById(id) : mapper.selectById(id));
        // 复用该域的本人制单 + 全部来源订单可见性；不借 FG 上游专用权限。
        service.checkVisible(id);
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        // 标签是单头元数据，与改备注相同；已确认允许，关闭后拒绝。
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == MrpStatus.DRAFT || row.getStatus() == MrpStatus.CONFIRMED);
        return target;
    }
}
