package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import com.lingmao.scm.security.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 物料余额沿用库存列表权限；此表按仓库/SKU/物权共享，不具备公司成员 ACL。 */
@Component
public class StockAuditTargetGuard implements AuditTargetGuard {
    private static final Set<String> TABLES = Set.of("inv_stock");
    private final StockMapper stocks;

    public StockAuditTargetGuard(StockMapper stocks) { this.stocks = stocks; }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void check(String table, long id) {
        if (!TABLES.contains(table) || id <= 0) throw missing();
        if (!LoginUser.hasAuthority("inventory:stock:list")) throw new BizException(403, "没有查看物料库存的权限");
        if (stocks.selectById(id) == null) throw missing();
    }

    @Override public Root check(String table, long id, HistoricalRootGuard historical) {
        check(table, id);
        return new Root(table, id);
    }

    private static BizException missing() { return new BizException(404, "库存审计对象不存在或无权查看"); }
}
