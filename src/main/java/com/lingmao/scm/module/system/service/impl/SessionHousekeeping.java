package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public record SessionHousekeeping(JdbcTemplate jdbc, DeploymentNamespace namespace) implements HousekeepingTask {
    public SessionHousekeeping {
        jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        // 三条语句各有超时，在 60 秒清理租约的前 30 秒内做完或失败。
        jdbc.setQueryTimeout(10);
    }
    @Override public String name() { return "session-cleanup"; }
    @Override public void runUntil(java.util.function.BooleanSupplier stopping) {
        if (stopping.getAsBoolean()) return;
        jdbc.update("DELETE FROM sys_login_session WHERE (tenant_id,jti) IN (SELECT tenant_id,jti FROM sys_login_session WHERE tenant_id=? AND expires_at<clock_timestamp() LIMIT 1000)", namespace.tenant());
        if (stopping.getAsBoolean()) return;
        jdbc.update("DELETE FROM sys_refresh_session WHERE id IN (SELECT id FROM sys_refresh_session WHERE tenant_id=? AND expires_at<clock_timestamp()-interval '1 day' LIMIT 1000)", namespace.tenant());
        if (stopping.getAsBoolean()) return;
        jdbc.update("DELETE FROM sys_outbox WHERE id IN (SELECT id FROM sys_outbox WHERE tenant_key=? AND kind='CACHE_INVALIDATE' AND status='SUCCEEDED' AND updated_at<clock_timestamp()-interval '7 days' LIMIT 1000)", namespace.tenant());
    }
}
