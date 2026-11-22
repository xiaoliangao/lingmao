package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * 改密、停用、删除时一起失效；parentJti 是换它的那张登录令牌（同一个人、普通登录、没过期），那张退出时这张一起删。
     * 换的这一刻登录已经失效就不登记，抛 AuthRejected。
     * 要在事务里调：先给那张登录令牌加共享锁，和同时进行的退出、刷新（它们先加排他锁，见 lockAccess）排队，
     * 不会登记出一张挂在已删掉的登录下面、退出也删不到的令牌
     */
    public void saveScoped(String jti, long userId, String scope, String parentJti, Instant expiresAt) {
        var parent = jdbc.queryForList("""
                SELECT p.session_version FROM sys_login_session p JOIN sys_user u ON u.id=p.user_id
                WHERE p.tenant_id=? AND p.jti=? AND p.user_id=? AND p.scope IS NULL AND p.expires_at>?
                  AND u.status=1 AND NOT u.deleted AND p.session_version=u.session_version
                FOR SHARE OF p
                """, Long.class, namespace.tenant(), parentJti, userId, at(clock.instant()));
        if (parent.isEmpty()) throw new AuthRejected();
        jdbc.update("""
                INSERT INTO sys_login_session(tenant_id,jti,user_id,session_version,expires_at,scope,parent_jti)
                VALUES(?,?,?,?,?,?,?)
                """, namespace.tenant(), jti, userId, parent.getFirst(), at(expiresAt), scope, parentJti);
    }

    /** 一张有效令牌对应的会话：谁、什么用途（普通登录是 null） */
    public record Session(long userId, String scope) {
    }

    /** 令牌还有效（没过期、用户在用、没改过密；有用途的令牌还要换它的那张登录还在）就返回会话，否则 null */
    public Session lookup(String jti) {
        if (jti == null || jti.length() > 64) return null;
        var rows = jdbc.query("""
                SELECT s.user_id, s.scope FROM sys_login_session s JOIN sys_user u ON u.id=s.user_id
                WHERE s.tenant_id=? AND s.jti=? AND s.expires_at>?
                  AND u.status=1 AND NOT u.deleted AND s.session_version=u.session_version
                  AND (s.parent_jti IS NULL OR EXISTS (SELECT 1 FROM sys_login_session p
                       WHERE p.tenant_id=s.tenant_id AND p.jti=s.parent_jti AND p.scope IS NULL))
                """, (rs, n) -> new Session(rs.getLong("user_id"), rs.getString("scope")),
                namespace.tenant(), jti, at(clock.instant()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 普通登录令牌是谁的；有用途的令牌（助手）不算，返回 null */
    @Override
    public Long getUserId(String jti) {
        Session session = lookup(jti);
        return session == null || session.scope() != null ? null : session.userId();
    }

    /** 退出：作废这张登录的刷新会话，删掉它和从它换来的助手令牌。放在一个事务里，lockAccess 的锁才管用 */
    @Override
    @Transactional
    public void remove(String jti) {
        if (jti == null) return;
        jdbc.update("UPDATE sys_refresh_session SET revoked=true WHERE tenant_id=? AND access_jti::text=?",
                namespace.tenant(), jti);
        removeAccess(jti);
    }

    /**
     * 给一张登录令牌加排他锁（在事务里，到提交为止）：退出、刷新先锁住，同时在换助手令牌的那边（saveScoped 的共享锁）
     * 要么先换完、这边接着把它一起删或改挂，要么等这边提交、看到登录已经没了换不到
     */
    void lockAccess(String jti) {
        jdbc.queryForList("SELECT 1 FROM sys_login_session WHERE tenant_id=? AND jti=? FOR UPDATE", Integer.class, namespace.tenant(), jti);
    }

    /** 刷新换了新的登录令牌：从旧令牌换来的助手令牌改挂到新令牌下（不删，免得每次刷新都把正在用的助手打断） */
    void reparent(String oldJti, String newJti) {
        lockAccess(oldJti);
        jdbc.update("UPDATE sys_login_session SET parent_jti=? WHERE tenant_id=? AND parent_jti=?", newJti, namespace.tenant(), oldJti);
    }

    /** 删掉这张令牌，连同从它换来的助手令牌（退出、刷新令牌被吊销时） */
    void removeAccess(String jti) {
        lockAccess(jti);
        jdbc.update("DELETE FROM sys_login_session WHERE tenant_id=? AND (jti=? OR parent_jti=?)", namespace.tenant(), jti, jti);
    }

    static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
