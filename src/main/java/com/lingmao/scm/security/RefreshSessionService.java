package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.AuthTokens;
import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** 锁住一行会话族，原子地轮换 Cookie 和登记的 access token。 */
@Service
public class RefreshSessionService {
    private static final Duration LIFETIME = Duration.ofDays(7);
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final LoginSessionStore sessions;
    private final JwtUtil jwt;
    private final Clock clock;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public RefreshSessionService(JdbcTemplate jdbc, DeploymentNamespace namespace, LoginSessionStore sessions,
                                 JwtUtil jwt, Clock clock, @Value("${lingmao.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.sessions = sessions;
        this.jwt = jwt;
        this.clock = clock;
        this.key = new SecretKeySpec(Decoders.BASE64.decode(secret), "HmacSHA256");
    }

    @Transactional
    public AuthTokens issue(String accessToken) {
        var claims = jwt.parse(accessToken);
        Long userId = sessions.getUserId(claims.getId());
        if (userId == null) throw new AuthRejected();
        var versions = jdbc.queryForList("SELECT session_version FROM sys_user WHERE id=? AND status=1 AND NOT deleted FOR SHARE",
                Long.class, userId);
        if (versions.isEmpty() || sessions.getUserId(claims.getId()) == null) throw new AuthRejected();
        UUID family = UUID.randomUUID();
        String nonce = randomToken();
        String browser = randomToken();
        String refresh = derive(family, nonce);
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO sys_refresh_session(id,tenant_id,user_id,session_version,browser_hash,token_hash,
                    rotation_nonce,access_jti,issued_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, family, namespace.tenant(), userId, versions.getFirst(), hash(browser), hash(refresh), nonce,
                UUID.fromString(claims.getId()), LoginSessionStore.at(claims.getIssuedAt().toInstant()),
                LoginSessionStore.at(now.plus(LIFETIME)));
        return new AuthTokens(accessToken, refresh, browser, LIFETIME.toSeconds());
    }

    @Transactional
    public AuthTokens rotate(String refresh, String browser) {
        if (!valid(refresh) || !valid(browser)) throw new AuthRejected();
        String presented = hash(refresh);
        var rows = jdbc.query("""
                SELECT * FROM sys_refresh_session
                WHERE tenant_id=? AND (token_hash=? OR previous_hash=?) FOR UPDATE
                """, (rs, n) -> new Family(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                rs.getLong("session_version"), rs.getString("browser_hash"), rs.getString("token_hash"),
                rs.getString("previous_hash"), rs.getString("rotation_nonce"),
                rs.getObject("access_jti", UUID.class), rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("grace_until", OffsetDateTime.class), rs.getInt("grace_replays"), rs.getBoolean("revoked")),
                namespace.tenant(), presented, presented);
        if (rows.size() != 1) throw new AuthRejected();
        Family row = rows.getFirst();
        Instant now = clock.instant();
        if (row.revoked || !now.isBefore(row.expires) || !same(row.browserHash, hash(browser))) throw new AuthRejected();
        var versions = jdbc.queryForList("SELECT session_version FROM sys_user WHERE id=? AND status=1 AND NOT deleted FOR SHARE",
                Long.class, row.userId);
        if (versions.isEmpty() || versions.getFirst() != row.version) throw new AuthRejected();

        if (!same(presented, row.tokenHash)) {
            // 固定的、绑定浏览器的宽限期只重放已提交的后继令牌，绝不延长。
            if (row.graceUntil == null || !now.isBefore(row.graceUntil.toInstant()) || row.replays >= 2)
                throw new AuthRejected();
            jdbc.update("UPDATE sys_refresh_session SET grace_replays=grace_replays+1 WHERE id=?", row.id);
            return tokens(row, browser, now);
        }

        String nonce = randomToken();
        UUID jti = UUID.randomUUID();
        Instant issued = now.truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("""
                UPDATE sys_refresh_session SET previous_hash=token_hash,token_hash=?,rotation_nonce=?,access_jti=?,
                    issued_at=?,grace_until=?,grace_replays=0 WHERE id=?
                """, hash(derive(row.id, nonce)), nonce, jti, LoginSessionStore.at(issued),
                LoginSessionStore.at(now.plusSeconds(3)), row.id);
        sessions.reparent(row.jti.toString(), jti.toString());
        sessions.removeAccess(row.jti.toString());
        sessions.saveVersion(jti.toString(), row.userId, row.version, issued);
        return tokens(new Family(row.id, row.userId, row.version, row.browserHash, null, row.tokenHash,
                nonce, jti, issued, row.expires, null, 0, false), browser, now);
    }

    @Transactional
    public void revoke(String refresh, String browser) {
        if (!valid(refresh) || !valid(browser)) return;
        String tokenHash = hash(refresh);
        var revoked = jdbc.queryForList("""
                UPDATE sys_refresh_session SET revoked=true WHERE tenant_id=? AND browser_hash=?
                  AND (token_hash=? OR previous_hash=?) RETURNING access_jti::text
                """, String.class, namespace.tenant(), hash(browser), tokenHash, tokenHash);
        revoked.forEach(sessions::removeAccess);
    }

    private AuthTokens tokens(Family row, String browser, Instant now) {
        return new AuthTokens(jwt.createToken(row.userId, row.jti.toString(), row.issued), derive(row.id, row.nonce),
                browser, Math.max(0, Duration.between(now, row.expires).toSeconds()));
    }

    private String derive(UUID family, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                    ("lingmao-refresh-v1\n" + namespace.tenant() + "\n" + family + "\n" + nonce).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("Refresh HMAC unavailable", impossible);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private record Family(UUID id, long userId, long version, String browserHash, String tokenHash,
                          String previousHash, String nonce, UUID jti, Instant issued, Instant expires,
                          OffsetDateTime graceUntil, int replays, boolean revoked) { }
}
