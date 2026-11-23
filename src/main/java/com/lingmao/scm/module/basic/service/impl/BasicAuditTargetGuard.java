package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.mapper.CompanyMapper;
import com.lingmao.scm.module.basic.mapper.WarehouseMapper;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import com.lingmao.scm.security.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 基础主档按现有共享读权限验证真实行；客户域尚未开放，不能借审计接口提前开放。 */
@Component
public class BasicAuditTargetGuard implements AuditTargetGuard {
    private static final Set<String> TABLES = Set.of("bas_company", "bas_warehouse", "bas_customer");
    private final CompanyMapper companies;
    private final WarehouseMapper warehouses;

    public BasicAuditTargetGuard(CompanyMapper companies, WarehouseMapper warehouses) {
        this.companies = companies;
        this.warehouses = warehouses;
    }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void check(String table, long id) {
        if (!TABLES.contains(table) || id <= 0 || table.equals("bas_customer")) throw missing();
        if (!LoginUser.hasAuthority("basic:basics:list")) throw new BizException(403, "没有查看基础资料的权限");
        boolean exists = table.equals("bas_company") ? companies.selectById(id) != null : warehouses.selectById(id) != null;
        if (!exists) throw missing();
        // 现有主档在部署内共享，没有主体归属列；不伪造 X-Company-Id 绑定。
    }

    @Override public Root check(String table, long id, HistoricalRootGuard historical) {
        check(table, id);
        return new Root(table, id);
    }

    private static BizException missing() { return new BizException(404, "审计对象不存在或无权查看"); }
}
