package com.lingmao.scm.module.price.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.price.mapper.PriceMapper;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 价格档案的审计读取与真实价格类型一致，不能用另一价格域或全局审计权限替代。 */
@Component
public class PriceAuditTargetGuard implements AuditTargetGuard {
    private static final Set<String> TABLES = Set.of("prc_price");
    private final PriceMapper prices;

    public PriceAuditTargetGuard(PriceMapper prices) { this.prices = prices; }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void check(String table, long id) {
        if (!TABLES.contains(table) || id <= 0) throw missing();
        if (!PricePerms.has(PricePerms.LIST)) throw new BizException(403, "没有查看价格档案的权限");
        var price = prices.selectById(id);
        if (price == null || price.getType() == null) throw missing();
        PricePerms.checkRead(price.getType());
    }

    @Override public Root check(String table, long id, HistoricalRootGuard historical) {
        check(table, id);
        return new Root(table, id);
    }

    private static BizException missing() { return new BizException(404, "价格审计对象不存在或无权查看"); }
}
