package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.security.DataScope;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** 回货标签只改元数据；不提供数量、版本或状态编辑入口。 */
@Component
public class ProductionReceiptTagProvider implements TagObjectProvider {
    private static final Set<DocStatus> OPEN = Set.of(DocStatus.APPROVED, DocStatus.RUNNING, DocStatus.DONE);
    private final ProductionReceiptMapper receipts;
    private final ProductionOrderMapper productions;
    private final ProductionSupport support;
    public ProductionReceiptTagProvider(ProductionReceiptMapper receipts, ProductionOrderMapper productions, ProductionSupport support) {
        this.receipts = receipts; this.productions = productions; this.support = support;
    }
    @Override public TagObjectType type() { return TagObjectType.PRODUCTION_RECEIPT; }
    @Override public Target resolve(long id, Access access) {
        TagObjectProvider.permission(access, "production:order:list", "production:receipt:add");
        ProductionReceipt row = TagObjectProvider.present(receipts.selectById(id));
        ProductionOrder parent;
        if (access.locks()) {
            // 与作废路径同序；此后不再反向获取需求订单锁。
            parent = TagObjectProvider.present(productions.lockById(row.getPrdOrderId()));
            row = TagObjectProvider.present(receipts.lockById(id));
            if (!Objects.equals(row.getPrdOrderId(), parent.getId())) throw new BizException(404, "回货单不存在或无权访问");
        } else {
            parent = TagObjectProvider.present(productions.selectById(row.getPrdOrderId()));
        }
        if (access == Access.READ) support.visible(parent);
        else DataScope.current(false).check(parent.getCreateBy(), parent.getMerchId());
        if (!Objects.equals(parent.getCompanyId(), row.getCompanyId())) throw new BizException(404, "回货单不存在或无权访问");
        Target target = TagObjectProvider.target(type(), id, row.getCompanyId());
        if (access.locks()) TagObjectProvider.editable(row.getStatus() == DocStatus.APPROVED && OPEN.contains(parent.getStatus()));
        return target;
    }
}
