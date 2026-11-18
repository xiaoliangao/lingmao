package com.lingmao.scm;

import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.UUID;

/** Explicit identities for service integration tests; HTTP boundary tests use real authenticated requests. */
public final class TestIdentity {
    private TestIdentity() { }

    public static void ensureUser(JdbcTemplate jdbc, long user) {
        jdbc.update("""
                INSERT INTO sys_user(id, username, password, nickname)
                OVERRIDING SYSTEM VALUE VALUES(?, ?, 'unusable-test-password', ?) ON CONFLICT(id) DO NOTHING
                """, user, "it-identity-" + user, "IT identity " + user);
    }

    /** Seed the same narrow permissions that the fixture puts in its SecurityContext. */
    public static void grant(JdbcTemplate jdbc, long user, Collection<String> permissions) {
        ensureUser(jdbc, user);
        String code = "it-auth-" + UUID.randomUUID().toString().substring(0, 16);
        Long role = jdbc.queryForObject("""
                INSERT INTO sys_role(code, name, data_scope) VALUES(?, ?, ?) RETURNING id
                """, Long.class, code, code, permissions.contains("data:all") ? "ALL" : "SELF");
        jdbc.update("INSERT INTO sys_user_role(user_id, role_id) VALUES(?, ?)", user, role);
        for (String permission : permissions) {
            if (permission.equals("data:all")) continue;
            int inserted = jdbc.update("""
                    INSERT INTO sys_role_menu(role_id, menu_id) SELECT ?, id FROM sys_menu WHERE perms=?
                    ON CONFLICT DO NOTHING
                    """, role, permission);
            if (inserted == 0) throw new AssertionError("Missing fixture permission: " + permission);
        }
    }

    public static void login(long user, Collection<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, "it-service"), null,
                permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    public static void company(long company) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/it-service-fixture");
        request.addHeader("X-Company-Id", Long.toString(company));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }
}
