package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P12：真实 HTTP、Cookie、语句级触发器、两个数据库连接和整事务回滚。 */
@Import(A2AuthorizationRevocationHttpIT.ReadGateConfig.class)
class A2AuthorizationRevocationHttpIT extends A2BusinessFixture {
    @Autowired DataSource dataSource;
    @Autowired PermissionReadGate readGate;
    private static final String SECRET = "A2-Business-123456";
    private record Session(Account account, String cookies) { }
    private record MenuTree(long parent, long button, String permission) { }

    @Test void roleScopeAndStatusRevokeEveryAffectedSessionButNotOtherRoles() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "ALL", "order:order:list");
        Account peer = peer(target.role(), company), unrelated = custom(company, "ALL", "order:order:list");
        Session first = session(target), second = session(target), peerSession = session(peer), untouched = session(unrelated);
        long before = version(target), peerBefore = version(peer), otherBefore = version(unrelated), adminBefore = version(admin);

        ok(admin, "PUT", "/api/roles/" + target.role(), roleBody(target.role(), 1, "SELF"), null);
        assertThat(version(target)).isEqualTo(before + 1);
        assertThat(version(peer)).isEqualTo(peerBefore + 1);
        assertThat(version(unrelated)).isEqualTo(otherBefore);
        assertThat(version(admin)).isEqualTo(adminBefore);
        for (Session revoked : List.of(first, second, peerSession)) assertRevoked(revoked);
        assertAlive(untouched);

        Session afterScope = session(target);
        assertThat(permissions.loadPermissions(target.id())).doesNotContain("data:all");
        ok(admin, "PUT", "/api/roles/" + target.role(), roleBody(target.role(), 0, "SELF"), null);
        assertRevoked(afterScope);
        Session afterDisable = session(target); // 账号仍启用；重新登录的角色权限已收回。
        assertAlive(afterDisable);
        assertForbidden(afterDisable.account(), "/api/orders");
    }

    @Test void removingRoleMenusRevokesAccessAndRefreshThenFreshAccessGets403() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", "order:order:list");
        Session old = session(target);
        long before = version(target);
        ok(admin, "PUT", "/api/roles/" + target.role() + "/menus", map("ids", List.of()), null);
        assertRevoked(old);
        assertThat(version(target)).isEqualTo(before + 1);
        Session current = session(target);
        assertAlive(current);
        assertForbidden(current.account(), "/api/orders");
    }

    @Test void directRoleDeletionAlsoRevokesUsersBecauseLegacyMembershipsHaveNoForeignKey() throws Exception {
        Account target = custom(company(), "SELF", "order:order:list"); Session old = session(target);
        long before = version(target);
        assertThat(jdbc.update("DELETE FROM sys_role WHERE id=?", target.role())).isOne();
        assertThat(version(target)).isEqualTo(before + 1);
        assertRevoked(old);
        assertForbidden(session(target).account(), "/api/orders");
    }

    @Test void replacingUserRolesRevokesOnlyThatUserAndSameSetSaveIsANoop() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", "order:order:list");
        Account peer = peer(target.role(), company), replacement = custom(company, "SELF");
        Session old = session(target), other = session(peer);
        long before = version(target), peerBefore = version(peer);
        ok(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(replacement.role())), null);
        assertThat(version(target)).isGreaterThan(before);
        assertThat(version(peer)).isEqualTo(peerBefore);
        assertRevoked(old); assertAlive(other);
        Session current = session(target);
        assertForbidden(current.account(), "/api/orders");
        long currentVersion = version(target);
        ok(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(replacement.role(), replacement.role())), null);
        assertThat(version(target)).isEqualTo(currentVersion);
        assertAlive(current);
    }

    @Test void disablingParentRevokesDescendantGrantAndImplicitAdminButNotUnrelatedUsers() throws Exception {
        MenuTree tree = menuTree();
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", tree.permission());
        Account unrelated = custom(company, "SELF", "order:order:list");
        Session administrator = session(admin), child = session(target), other = session(unrelated);
        long childBefore = version(target), adminBefore = version(admin), otherBefore = version(unrelated);
        assertThat(permissions.loadPermissions(target.id())).contains(tree.permission());
        ok(administrator.account(), "PUT", "/api/menus/" + tree.parent(),
                map("parentId", 0, "name", "A2 parent", "type", 1, "path", "/a2-parent-" + tree.parent(), "status", 0), null);
        assertThat(version(target)).isEqualTo(childBefore + 1);
        assertThat(version(admin)).isEqualTo(adminBefore + 1);
        assertThat(version(unrelated)).isEqualTo(otherBefore);
        assertRevoked(administrator); assertRevoked(child); assertAlive(other);
        assertThat(permissions.loadPermissions(target.id())).doesNotContain(tree.permission());
        assertAlive(session(target));
    }

    @Test void permissionEditThenMovingIntoAdminOnlyRevokesOldGrantsAndDeletingRevokesAdmins() throws Exception {
        MenuTree tree = menuTree();
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", tree.permission());
        Session old = session(target);
        String replacement = "a2:changed:" + suffix();
        ok(admin, "PUT", "/api/menus/" + tree.button(),
                map("parentId", tree.parent(), "name", "A2 button", "type", 3, "perms", replacement), null);
        assertRevoked(old);
        assertThat(permissions.loadPermissions(target.id())).contains(replacement).doesNotContain(tree.permission());
        Session afterEdit = session(target), operator = session(admin);
        long adminRoot = jdbc.queryForObject("SELECT id FROM sys_menu WHERE admin_only AND parent_id=0 AND NOT deleted AND status=1 ORDER BY id LIMIT 1", Long.class);
        ok(operator.account(), "PUT", "/api/menus/" + tree.button(),
                map("parentId", adminRoot, "name", "A2 private button", "type", 3, "perms", replacement), null);
        assertRevoked(afterEdit); assertRevoked(operator);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_role_menu WHERE menu_id=?", Long.class, tree.button())).isZero();
        assertThat(permissions.loadPermissions(target.id())).doesNotContain(replacement);
        Session beforeDelete = session(admin);
        ok(beforeDelete.account(), "DELETE", "/api/menus/" + tree.button(), null, null);
        assertRevoked(beforeDelete);
    }

    @Test void cosmeticChangesAndUnchangedGrantSetsPreserveIssuedSessions() throws Exception {
        MenuTree tree = menuTree();
        long company = company();
        Account admin = role("admin", company), target = custom(company, "ALL", tree.permission());
        Session old = session(target), operator = session(admin);
        long before = version(target), adminBefore = version(admin);
        Map<String, Object> role = roleBody(target.role(), 1, "ALL");
        role.put("name", "A2 renamed only"); role.put("sort", 17); role.put("remark", "display only");
        ok(admin, "PUT", "/api/roles/" + target.role(), role, null);
        ok(admin, "PUT", "/api/roles/" + target.role() + "/menus", map("ids", List.of(tree.button())), null);
        ok(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(target.role())), null);
        ok(admin, "PUT", "/api/menus/" + tree.button(),
                map("parentId", tree.parent(), "name", "A2 cosmetic rename", "type", 3, "perms", tree.permission(), "sort", 29), null);
        assertThat(version(target)).isEqualTo(before);
        assertThat(version(admin)).isEqualTo(adminBefore);
        assertAlive(old); assertAlive(operator);
    }

    @Test void failedRealGrantRollsBackMembershipVersionAuditAndBothKindsOfSession() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", "order:order:list");
        Account replacement = custom(company, "SELF");
        Session old = session(target);
        long before = version(target), auditBefore = userAuditCount(target);
        String marker = "a2_grant_failure_" + suffix();
        jdbc.execute("CREATE FUNCTION " + marker + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id="
                + target.id() + " AND NEW.role_id=" + replacement.role()
                + " THEN RAISE EXCEPTION 'A2 synthetic grant rejection'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " AFTER INSERT ON sys_user_role FOR EACH ROW EXECUTE FUNCTION " + marker + "()");
        try {
            var failed = raw(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(replacement.role())), null, null);
            assertThat(node(failed).path("code").intValue()).isEqualTo(500);
            assertThat(version(target)).isEqualTo(before);
            assertThat(userAuditCount(target)).isEqualTo(auditBefore);
            assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?", Long.class, target.id())).containsExactly(target.role());
            assertAlive(old);
            assertThat(refresh(old).statusCode()).isEqualTo(200);
        } finally {
            jdbc.execute("DROP TRIGGER " + marker + " ON sys_user_role");
            jdbc.execute("DROP FUNCTION " + marker + "()");
        }
    }

    @Test void explicitAtomicVersionBumpsAreNotSwallowedAndCannotMoveBackwards() throws Exception {
        Account target = custom(company(), "SELF"); Session old = session(target);
        long before = version(target);
        assertThat(jdbc.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?", target.id())).isOne();
        assertThat(version(target)).isEqualTo(before + 1);
        assertRevoked(old);
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_user SET session_version=? WHERE id=?", before, target.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        CountDownLatch start = new CountDownLatch(1);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> tasks.submit(() -> {
                start.await(); return jdbc.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?", target.id());
            })).toList();
            start.countDown();
            for (var result : futures) assertThat(result.get(15, TimeUnit.SECONDS)).isOne();
        }
        assertThat(version(target)).isEqualTo(before + 13);
    }

    @Test void concurrentWholeRoleReplacementsCannotLeaveTheUnionOfTwoGrants() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF");
        Account first = custom(company, "SELF", "order:order:list"), second = custom(company, "SELF", "supplier:supplier:list");
        Session old = session(target);
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            lock(blocker, "sys_user", target.id());
            var one = pool.submit(() -> call(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(first.role())), null));
            var two = pool.submit(() -> call(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(second.role())), null));
            try { awaitWaiters("%SELECT id FROM sys_user WHERE id%FOR NO KEY UPDATE%", 2); }
            finally { blocker.commit(); }
            assertThat(one.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
            assertThat(two.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
        }
        List<Long> finalRoles = jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?", Long.class, target.id());
        assertThat(finalRoles).hasSize(1);
        assertThat(finalRoles.getFirst()).isIn(first.role(), second.role());
        assertRevoked(old);
    }

    @Test void concurrentWholeMenuReplacementsCannotLeaveTheUnionOfTwoGrants() throws Exception {
        long company = company();
        Account admin = role("admin", company), target = custom(company, "SELF", "system:dict:list");
        Session old = session(target);
        long first = permissionId("order:order:list"), second = permissionId("supplier:supplier:list");
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            lock(blocker, "sys_role", target.role());
            var one = pool.submit(() -> call(admin, "PUT", "/api/roles/" + target.role() + "/menus", map("ids", List.of(first)), null));
            var two = pool.submit(() -> call(admin, "PUT", "/api/roles/" + target.role() + "/menus", map("ids", List.of(second)), null));
            try { awaitWaiters("%SELECT id FROM sys_role WHERE id%FOR NO KEY UPDATE%", 2); }
            finally { blocker.commit(); }
            assertThat(one.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
            assertThat(two.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
        }
        List<Long> finalMenus = jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=?", Long.class, target.role());
        assertThat(finalMenus).hasSize(1);
        assertThat(finalMenus.getFirst()).isIn(first, second);
        assertRevoked(old);
    }

    @Test void twoRoleUpdatesSharingUsersQueueInOneUserOrderWithoutLostIncrements() throws Exception {
        long company = company();
        Account admin = role("admin", company), one = custom(company, "ALL"), two = custom(company, "ALL");
        // 两个真实角色都关联这两个用户，关联插入顺序刻意相反。
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?),(?,?)", two.id(), one.role(), one.id(), two.role());
        Session first = session(one), second = session(two);
        long firstBefore = version(one), secondBefore = version(two);
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            lock(blocker, "sys_user", Math.min(one.id(), two.id()));
            var a = pool.submit(() -> call(admin, "PUT", "/api/roles/" + one.role(), roleBody(one.role(), 1, "SELF"), null));
            var b = pool.submit(() -> call(admin, "PUT", "/api/roles/" + two.role(), roleBody(two.role(), 1, "SELF"), null));
            try { awaitWaiters("%UPDATE sys_role%", 2); }
            finally { blocker.commit(); }
            assertThat(a.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
            assertThat(b.get(15, TimeUnit.SECONDS).path("code").intValue()).isZero();
        }
        assertThat(version(one)).isEqualTo(firstBefore + 2);
        assertThat(version(two)).isEqualTo(secondBefore + 2);
        assertRevoked(first); assertRevoked(second);
    }

    @Test void grantCommittingBetweenSessionAndPermissionReadsCannotElevateAnOldToken() throws Exception {
        Account target = custom(company(), "SELF"); Session old = session(target);
        long before = version(target);
        ReadPause pause = new ReadPause(target.id()); readGate.current = pause;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var inFlight = pool.submit(() -> raw(old.account(), "GET", "/api/auth/me", null, null, null));
            try {
                assertThat(pause.entered.await(8, TimeUnit.SECONDS)).as("real filter finished its first session check").isTrue();
                assertThat(jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)", target.role(), permissionId("order:order:list"))).isOne();
            } finally { pause.resume.countDown(); }
            var response = inFlight.get(15, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(node(response).path("code").intValue()).isEqualTo(401);
            assertThat(version(target)).isEqualTo(before + 1);
        } finally { pause.resume.countDown(); readGate.current = null; }
        assertThat(permissions.loadPermissions(target.id())).contains("order:order:list");
        assertRevoked(old);
        assertAlive(session(target));
    }

    @Test void aNewLowerIdMemberCannotEnterBetweenDeleteAndInsertAndInvertTheUserLockOrder() throws Exception {
        long company = company();
        Account lower = custom(company, "ALL"), middle = custom(company, "ALL", "order:order:list");
        Account higher = peer(middle.role(), company), admin = role("admin", company);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", higher.id(), lower.role());
        Session lowSession = session(lower), midSession = session(middle), highSession = session(higher);
        long replacementMenu = permissionId("supplier:supplier:list");
        Map<String, Object> scopeChange = roleBody(lower.role(), 1, "SELF");
        String marker = "zz_a2_replace_pause_" + suffix();
        long pauseKey = 8_400_000_000L + higher.id();
        // 字母顺序使它晚于实际 authorization_sessions_delete：第一事务已锁住 middle/higher，
        // 暂停位置恰是整体替换的 DELETE 已结束、INSERT 尚未开始。
        jdbc.execute("CREATE FUNCTION " + marker + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF EXISTS(SELECT 1 FROM removed_grants WHERE role_id=" + middle.role() + ") THEN "
                + "PERFORM pg_advisory_xact_lock(" + pauseKey + "); END IF; RETURN NULL; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " AFTER DELETE ON sys_role_menu REFERENCING OLD TABLE AS removed_grants "
                + "FOR EACH STATEMENT EXECUTE FUNCTION " + marker + "()");
        try {
            try (Connection blocker = dataSource.getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                advisory(blocker, pauseKey, true);
                boolean paused = true;
                try {
                    var replacement = pool.submit(() -> call(admin, "PUT", "/api/roles/" + middle.role() + "/menus",
                            map("ids", List.of(replacementMenu)), null));
                    awaitWaiters("%DELETE FROM sys_role_menu%", 1);
                    var membership = pool.submit(() -> call(admin, "PUT", "/api/users/" + lower.id() + "/roles",
                            map("ids", List.of(lower.role(), middle.role())), null));
                    // 红对照仍让真实第三事务继续：旧实现会先提交新成员；随后另一角色先锁 lower 再等 higher。
                    boolean membershipCommittedBeforeReplacement = awaitResultOrGraphWait(membership);
                    var roleChange = pool.submit(() -> call(admin, "PUT", "/api/roles/" + lower.role(), scopeChange, null));
                    awaitWaiters("%UPDATE sys_role%", membershipCommittedBeforeReplacement ? 1 : 2);
                    advisory(blocker, pauseKey, false);
                    paused = false;

                    List<Integer> codes = List.of(replacement.get(20, TimeUnit.SECONDS).path("code").intValue(),
                            membership.get(20, TimeUnit.SECONDS).path("code").intValue(),
                            roleChange.get(20, TimeUnit.SECONDS).path("code").intValue());
                    assertThat(codes).as("three real overlapping writes complete without a swallowed deadlock or retry").containsOnly(0);
                    assertThat(membershipCommittedBeforeReplacement).as("membership cannot change the affected set between replacement statements").isFalse();
                } finally { if (paused) advisory(blocker, pauseKey, false); }
            }
        } finally {
            jdbc.execute("DROP TRIGGER " + marker + " ON sys_role_menu");
            jdbc.execute("DROP FUNCTION " + marker + "()");
        }
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id", Long.class, lower.id()))
                .containsExactlyElementsOf(java.util.stream.Stream.of(lower.role(), middle.role()).sorted().toList());
        assertThat(jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=?", Long.class, middle.role())).containsExactly(replacementMenu);
        assertThat(permissions.loadPermissions(lower.id())).contains("supplier:supplier:list").doesNotContain("order:order:list");
        assertRevoked(lowSession); assertRevoked(midSession); assertRevoked(highSession);
    }

    private Session session(Account account) throws Exception {
        String username = jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, account.id());
        HttpResponse<byte[]> response = raw(null, "POST", "/api/auth/login", map("username", username, "password", SECRET), null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode login = node(response);
        assertThat(login.path("code").intValue()).isZero();
        String cookies = response.headers().allValues("Set-Cookie").stream().map(value -> value.split(";", 2)[0]).collect(Collectors.joining("; "));
        assertThat(cookies.contains("scm_refresh=") && cookies.contains("scm_browser=")).isTrue();
        return new Session(new Account(account.id(), account.role(), login.path("data").asText()), cookies);
    }

    private Account peer(long role, long company) throws Exception {
        String name = "a2_" + suffix();
        long user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname,default_company_id) VALUES(?,?,?,?) RETURNING id",
                Long.class, name, passwords.encode(SECRET), name, company);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user, role);
        return session(new Account(user, role, "")).account();
    }

    private void assertRevoked(Session session) throws Exception {
        var access = raw(session.account(), "GET", "/api/auth/me", null, null, null);
        var refresh = refresh(session);
        // 先取得两种真实响应，红测也保留两种会话是否继续成功的事实，绝不输出令牌或 Cookie。
        assertThat(List.of(access.statusCode(), refresh.statusCode())).as("old access and refresh are both unauthenticated")
                .containsExactly(401, 401);
        assertThat(node(access).path("code").intValue()).isEqualTo(401);
        assertThat(node(refresh).path("code").intValue()).isEqualTo(401);
    }

    private void assertAlive(Session session) throws Exception {
        var response = raw(session.account(), "GET", "/api/auth/me", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(node(response).path("code").intValue()).isZero();
    }

    private void assertForbidden(Account account, String path) throws Exception {
        var response = raw(account, "GET", path, null, null, null);
        // 现有 MVC 业务错误契约为 HTTP 200 + R.code=403；认证过滤器的撤销仍严格 HTTP 401。
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(node(response).path("code").intValue()).isEqualTo(403);
        assertThat(node(response).path("data").isNull() || node(response).path("data").isMissingNode()).isTrue();
    }

    private HttpResponse<byte[]> refresh(Session session) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/refresh"))
                .timeout(Duration.ofSeconds(15)).header("Cookie", session.cookies())
                .header("X-Requested-With", "XMLHttpRequest").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private long version(Account account) {
        return jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=?", Long.class, account.id());
    }

    private long company() {
        String code = suffix();
        return jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,'A2') RETURNING id",
                Long.class, code.substring(0, 6).toUpperCase(), "A2 auth company " + code);
    }

    private long userAuditCount(Account account) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table='sys_user' AND root_id=?", Long.class, account.id());
    }

    private Map<String, Object> roleBody(long role, int status, String scope) {
        Map<String, Object> actual = jdbc.queryForMap("SELECT code,name FROM sys_role WHERE id=?", role);
        return map("code", actual.get("code"), "name", actual.get("name"), "status", status, "dataScope", scope);
    }

    private long permissionId(String permission) {
        return jdbc.queryForObject("SELECT id FROM sys_menu WHERE perms=? AND NOT deleted AND NOT admin_only ORDER BY id LIMIT 1", Long.class, permission);
    }

    private MenuTree menuTree() {
        String permission = "a2:permission:" + suffix();
        long parent = jdbc.queryForObject("INSERT INTO sys_menu(parent_id,name,type,path) VALUES(0,'A2 parent',1,?) RETURNING id", Long.class, "/a2-parent-" + suffix());
        long button = jdbc.queryForObject("INSERT INTO sys_menu(parent_id,name,type,perms) VALUES(?,'A2 button',3,?) RETURNING id", Long.class, parent, permission);
        return new MenuTree(parent, button, permission);
    }

    private void lock(Connection connection, String table, long id) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR NO KEY UPDATE")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    private void awaitWaiters(String queryPattern, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                      AND (query ILIKE ? OR query ILIKE '%scm_lock_authorization_graph%')
                    """, Long.class, queryPattern);
            if (waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("both real authorization transactions must reach the PostgreSQL lock queue");
    }

    private boolean awaitResultOrGraphWait(Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) return true;
            long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                      AND query ILIKE '%scm_lock_authorization_graph%'
                    """, Long.class);
            if (waiting > 0) return false;
            Thread.sleep(20);
        }
        throw new AssertionError("membership write neither finished nor reached the authorization graph lock");
    }

    private void advisory(Connection connection, long key, boolean acquire) throws Exception {
        try (var statement = connection.prepareStatement("SELECT " + (acquire ? "pg_advisory_lock" : "pg_advisory_unlock") + "(?)")) {
            statement.setLong(1, key); statement.executeQuery().close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadGateConfig {
        @Bean PermissionReadGate permissionReadGate() { return new PermissionReadGate(); }
        @Bean PermissionReadBarrier permissionReadBarrier(PermissionReadGate gate) { return new PermissionReadBarrier(gate); }
    }
    static class PermissionReadGate { volatile ReadPause current; }
    static class ReadPause {
        final long user;
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        ReadPause(long user) { this.user = user; }
    }
    @Aspect
    static class PermissionReadBarrier {
        final PermissionReadGate gate;
        PermissionReadBarrier(PermissionReadGate gate) { this.gate = gate; }
        @Around("execution(* com.lingmao.scm.module.system.service.impl.PermissionServiceImpl.loadPermissions(..))")
        Object pauseAfterActualSessionCheck(ProceedingJoinPoint call) throws Throwable {
            ReadPause pause = gate.current;
            if (pause != null && ((Long) call.getArgs()[0]) == pause.user && pause.used.compareAndSet(false, true)) {
                pause.entered.countDown();
                if (!pause.resume.await(12, TimeUnit.SECONDS)) throw new AssertionError("grant transaction did not finish");
            }
            return call.proceed(); // 所有权限和版本仍从真实数据库读取，不替换查询结果。
        }
    }
}
