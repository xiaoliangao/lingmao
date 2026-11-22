package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 登记 access token、轮换刷新会话、凭据版本在同一个事务里。 */
@Component
public class LoginSessionStore implements SessionRegistry {
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final Duration ttl;
    private final Clock clock;

    public LoginSessionStore(JdbcTemplate jdbc, DeploymentNamespace namespace,
                             @Value("${lingmao.jwt.ttl}") Duration ttl, Clock clock) {
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 同时有人重置密码时，不能用已经不是当前的哈希登录。 */
    public void save(String jti, Long userId, String verifiedPasswordHash) {
        int inserted = jdbc.update("""
                INSERT INTO sys_login_session(tenant_id,jti,user_id,session_version,expires_at)
                SELECT ?,?,id,session_version,? FROM sys_user
                WHERE id=? AND password=? AND status=1 AND NOT deleted
                """, namespace.tenant(), jti, at(clock.instant().plus(ttl)), userId, verifiedPasswordHash);
        if (inserted != 1) throw new AuthRejected();
    }

    @Override
    public void saveVersion(String jti, long userId, long version, Instant issuedAt) {
        jdbc.update("""
                INSERT INTO sys_login_session(tenant_id,jti,user_id,session_version,expires_at)
                VALUES(?,?,?,?,?)
                """, namespace.tenant(), jti, userId, version, at(issuedAt.plus(ttl)));
    }

    /**
     * 登记一张有用途的令牌（现在只有助手令牌，RFC 017 §3.1）：照登录令牌一样记当前的 session_version，
     * 改密、停用、删除时一起失效；parentJti 是换它的那张登录令牌，那张退出时这张一起删。
     * 用户已经停用、删除（换的这一刻）就不登记，抛 AuthRejected
     */
    public void saveScoped(String jti, long userId, String scope, String parentJti, Instant expiresAt) {
        int inserted = jdbc.update("""
                INSERT INTO sys_login_session(tenant_id,jti,user_id,session_version,expires_at,scope,parent_jti)
                SELECT ?,?,id,session_version,?,?,? FROM sys_user
                WHERE id=? AND status=1 AND NOT deleted
                  AND EXISTS (SELECT 1 FROM sys_login_session p WHERE p.tenant_id=? AND p.jti=? AND p.scope IS NULL
                              AND p.expires_at>? AND p.session_version=sys_user.session_version)
                """, namespace.tenant(), jti, at(expiresAt), scope, parentJti, userId,
                namespace.tenant(), parentJti, at(clock.instant()));
        if (inserted != 1) throw new AuthRejected();
    }

    /** 一张有效令牌对应的会话：谁、什么用途（普通登录是 null） */
    public record Session(long userId, String scope) {
    }

    /** 令牌还有效（没过期、用户在用、没改过密）就返回会话，否则 null */
    public Session lookup(String jti) {
        if (jti == null || jti.length() > 64) return null;
        var rows = jdbc.query("""
                SELECT s.user_id, s.scope FROM sys_login_session s JOIN sys_user u ON u.id=s.user_id
                WHERE s.tenant_id=? AND s.jti=? AND s.expires_at>?
                  AND u.status=1 AND NOT u.deleted AND s.session_version=u.session_version
                """, (rs, n) -> new Session(rs.getLong("user_id"), rs.getString("scope")),
                namespace.tenant(), jti, at(clock.instant()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Long getUserId(String jti) {
        Session session = lookup(jti);
        return session == null ? null : session.userId();
    }

    @Override
    public void remove(String jti) {
        if (jti == null) return;
        jdbc.update("UPDATE sys_refresh_session SET revoked=true WHERE tenant_id=? AND access_jti::text=?",
                namespace.tenant(), jti);
        removeAccess(jti);
    }

    /** 刷新换了新的登录令牌：从旧令牌换来的助手令牌改挂到新令牌下（不删，免得每次刷新都把正在用的助手打断） */
    void reparent(String oldJti, String newJti) {
        jdbc.update("UPDATE sys_login_session SET parent_jti=? WHERE tenant_id=? AND parent_jti=?", newJti, namespace.tenant(), oldJti);
    }

    /** 删掉这张令牌，连同从它换来的助手令牌（退出、刷新令牌被吊销时） */
    void removeAccess(String jti) {
        jdbc.update("DELETE FROM sys_login_session WHERE tenant_id=? AND (jti=? OR parent_jti=?)", namespace.tenant(), jti, jti);
    }

    static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
