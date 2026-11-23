package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.security.DataScope;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ProductionOrderTagProvider implements TagObjectProvider {
    private final ProductionOrderMapper mapper;
    private final ApprovalService approvals;
    public ProductionOrderTagProvider(ProductionOrderMapper mapper, ApprovalService approvals) { this.mapper = mapper; this.approvals = approvals; }
    @Override public TagObjectType type() { return TagObjectType.PRODUCTION_ORDER; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "production:order:list", "production:order:edit");
        ProductionOrder row = TagObjectProvider.present(access.locks() ? mapper.lockById(id) : mapper.selectById(id));
        if (!DataScope.current(false).canSee(row.getCreateBy(), row.getMerchId())
                && !(access == Access.READ && row.getStatus() == DocStatus.PENDING
                && approvals.waitingForMe(BizType.PRODUCTION_ORDER, List.of(id)).contains(id))) {
            throw new BizException(404, "对象不存在或无权访问");
        }
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == DocStatus.DRAFT);
        return target;
    }
}
