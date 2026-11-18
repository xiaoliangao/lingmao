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

/** Access registrations share a transaction with refresh rotation and credential versions. */
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

    /** A concurrent password reset must not authenticate a hash that is no longer current. */
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

    @Override
    public Long getUserId(String jti) {
        if (jti == null || jti.length() > 64) return null;
        var ids = jdbc.queryForList("""
                SELECT s.user_id FROM sys_login_session s JOIN sys_user u ON u.id=s.user_id
                WHERE s.tenant_id=? AND s.jti=? AND s.expires_at>?
                  AND u.status=1 AND NOT u.deleted AND s.session_version=u.session_version
                """, Long.class, namespace.tenant(), jti, at(clock.instant()));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    @Override
    public void remove(String jti) {
        if (jti == null) return;
        jdbc.update("UPDATE sys_refresh_session SET revoked=true WHERE tenant_id=? AND access_jti::text=?",
                namespace.tenant(), jti);
        removeAccess(jti);
    }

    void removeAccess(String jti) {
        jdbc.update("DELETE FROM sys_login_session WHERE tenant_id=? AND jti=?", namespace.tenant(), jti);
    }

    static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
