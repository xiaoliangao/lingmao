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
        // 过期的登录令牌下面还挂着没过期的助手令牌时先留着（最多再留 30 分钟）：助手令牌认的是"换它的那张登录还在"，
        // 登录到点不该把 30 分钟的助手令牌提前作废；过期的登录令牌自己照样认不出、换不了（RFC 017 §13）
        jdbc.update("""
                DELETE FROM sys_login_session WHERE (tenant_id,jti) IN (
                  SELECT s.tenant_id,s.jti FROM sys_login_session s
                  WHERE s.tenant_id=? AND s.expires_at<clock_timestamp()
                    AND NOT EXISTS (SELECT 1 FROM sys_login_session c
                                    WHERE c.tenant_id=s.tenant_id AND c.parent_jti=s.jti AND c.expires_at>=clock_timestamp())
                  LIMIT 1000)
                """, namespace.tenant());
        if (stopping.getAsBoolean()) return;
        jdbc.update("DELETE FROM sys_refresh_session WHERE id IN (SELECT id FROM sys_refresh_session WHERE tenant_id=? AND expires_at<clock_timestamp()-interval '1 day' LIMIT 1000)", namespace.tenant());
        if (stopping.getAsBoolean()) return;
        jdbc.update("DELETE FROM sys_outbox WHERE id IN (SELECT id FROM sys_outbox WHERE tenant_key=? AND kind='CACHE_INVALIDATE' AND status='SUCCEEDED' AND updated_at<clock_timestamp()-interval '7 days' LIMIT 1000)", namespace.tenant());
        if (stopping.getAsBoolean()) return;
        // 钉钉消息（RFC 019 §4）：发出去的、发不出去的都留 90 天，管理后台"消息推送"看最近的；没有别的表引用它
        jdbc.update("DELETE FROM sys_outbox WHERE id IN (SELECT id FROM sys_outbox WHERE tenant_key=? AND kind='DINGTALK_MESSAGE' AND status IN ('SUCCEEDED','DEAD_LETTER','CANCELLED','EXPIRED') AND updated_at<clock_timestamp()-interval '90 days' LIMIT 1000)", namespace.tenant());
    }
}
