package com.lingmao.scm;

import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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

    /** 没给品类线（line:XXX）的看全部线，同超级管理员（RFC 013 §2）；测隔离的自己给 line:FOOTWEAR 这种 */
    public static void login(long user, Collection<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, "it-service"), null,
                withLines(permissions).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /** 审核人的 id（库里没有就插一个用户）：自己建的单不能自己审（SelfAudit，甲方 11-21 答 Q10），制单人是 user 的单给它审 */
    public static long auditorOf(JdbcTemplate jdbc, long user) {
        String username = "it-auditor-" + user;
        // 插不上（已经有了）就跳过，再查：并发的两个线程同时叫也不会撞唯一索引
        jdbc.update("INSERT INTO sys_user(username, password, nickname) VALUES(?, 'unusable-test-password', ?) ON CONFLICT DO NOTHING",
                username, "IT auditor " + user);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ? AND deleted = false", Long.class, username);
    }

    /**
     * 换成审核人做一件事，做完换回原来的人（出异常也换回）。审核人 = {@link #auditorOf} 当前登录的人，权限和当前的人一样，
     * 外加 data:all（SELF 范围看不见别人建的单）。IT 里一个人建单、审核时包一层：
     * <pre>TestIdentity.asAuditor(jdbc, () -> invDocService.act(id, "audit", new DocActionReq(v, null)));</pre>
     */
    public static <T> T asAuditor(JdbcTemplate jdbc, Supplier<T> action) {
        Authentication original = SecurityContextHolder.getContext().getAuthentication();
        if (original == null || !(original.getPrincipal() instanceof LoginUser me)) {
            throw new IllegalStateException("asAuditor 要先登录（TestIdentity.login）");
        }
        List<String> perms = new ArrayList<>(original.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        if (!perms.contains(RoleDataScope.ALL_AUTHORITY)) perms.add(RoleDataScope.ALL_AUTHORITY);
        login(auditorOf(jdbc, me.userId()), perms);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }

    public static void asAuditor(JdbcTemplate jdbc, Runnable action) {
        asAuditor(jdbc, () -> {
            action.run();
            return null;
        });
    }

    /** 权限里没有品类线的补一个 line:all */
    public static List<String> withLines(Collection<String> permissions) {
        List<String> out = new ArrayList<>(permissions);
        if (out.stream().noneMatch(p -> p.startsWith(LineScope.AUTHORITY_PREFIX))) {
            out.add(LineScope.ALL_AUTHORITY);
        }
        return out;
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
