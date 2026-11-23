package com.lingmao.scm.security;

import com.lingmao.scm.IsolatedRoleMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实际用户/角色 API、数据库触发器及审计响应；从不伪造一条成功审计记录。 */
class S4SecurityAuditHttpIT extends A2BusinessFixture {
    @Autowired javax.sql.DataSource dataSource;
    @Value("${spring.datasource.password:}") String databasePassword;
    private static final Set<String> USER_FIELDS = Set.of("id", "status", "deleted", "default_company_id", "session_version", "designer_no_locked");
    private static final Set<String> ROLE_FIELDS = Set.of("id", "code", "status", "deleted", "sort", "data_scope");

    @Test void actualUserStatusAndPasswordChangesPersistOnlySecurityFields() throws Exception {
        long company = company();
        Account admin = role("admin", company), reader = custom(company, "ALL", "system:audit:query");
        String username = "s4_" + suffix(), password = "S4-Synthetic-Login-9374", phone = "13800007164";
        long user = id(ok(admin, "POST", "/api/users", map("username", username, "password", password,
                "nickname", "S4 private nickname", "phone", phone, "dept", "S4 private department", "defaultCompanyId", company), null));
        String token = ok(null, "POST", "/api/auth/login", map("username", username, "password", password), null).asText();
        Account target = new Account(user, 0, token);
        String trace = "s4-disable-" + UUID.randomUUID();
        HttpResponse<byte[]> response = traced(admin, "PUT", "/api/users/" + user,
                map("nickname", "S4 private renamed", "phone", "13900007164", "dept", "S4 changed department",
                        "post", "S4 private post", "defaultCompanyId", company, "status", 0), trace);
        assertThat(node(response).path("code").intValue()).isZero();
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        assertThat(call(target, "GET", "/api/auth/me", null, null).path("code").intValue()).isEqualTo(401);
        JsonNode history = history(reader, "sys_user", user);
        JsonNode disable = byTrace(history, trace, "sys_user");
        assertThat(disable.path("rowId").asText()).isEqualTo(Long.toString(user));
        assertThat(disable.path("rowKey").isNull()).isTrue();
        assertChange(disable, "status", "1", "0");
        assertChange(disable, "session_version", "0", "1");
        assertThat(disable.path("actorUserId").asText()).isEqualTo(Long.toString(admin.id()));
        assertThat(disable.path("clientIp").asText()).isEqualTo("127.0.0.1");
        assertPersistedFields("sys_user", user, USER_FIELDS);
        String stored = jdbc.queryForObject("SELECT jsonb_agg(diff)::text FROM sys_data_log WHERE table_name='sys_user' AND row_id=?", String.class, user);
        for (String secret : List.of(username, password, phone, "private nickname", "private department", "private post", "$2a$", "$2b$"))
            assertThat(stored.contains(secret)).as("identity/credential values must never enter the security audit table").isFalse();
        ok(admin, "POST", "/api/users/" + user + "/reset-password", map("password", "S4-Replacement-Login-9374"), null);
        JsonNode reset = history(reader, "sys_user", user).get(0);
        assertChange(reset, "session_version", "1", "2");
        assertThat(reset.path("diff").properties().stream().map(Map.Entry::getKey).toList()).containsExactly("session_version");
        ok(admin, "PUT", "/api/users/" + user, map("nickname", "S4 enabled", "defaultCompanyId", company, "status", 1), null);
        JsonNode enable = history(reader, "sys_user", user).get(0);
        assertChange(enable, "status", "0", "1");
        assertChange(enable, "session_version", "2", "3");
        Account selfReader = custom(company, "SELF", "system:audit:query"), noAudit = custom(company, "ALL");
        for (Account refused : List.of(selfReader, noAudit))
            assertThat(call(refused, "GET", "/api/data-logs?rootTable=sys_user&rootId=" + user, null, null).path("code").intValue()).isEqualTo(403);
        evidence("user-security-history.json", history(reader, "sys_user", user));
    }

    @Test void roleScopeAndMembershipDiffsExposeRealCompositeStringKeys() throws Exception {
        long company = company();
        Account admin = role("admin", company), reader = custom(company, "ALL", "system:audit:query");
        String code = "s4_" + suffix();
        long role = id(ok(admin, "POST", "/api/roles", map("code", code, "name", "S4 role", "dataScope", "SELF"), null));
        String trace = "s4-role-" + UUID.randomUUID();
        var changed = traced(admin, "PUT", "/api/roles/" + role,
                map("code", code + "_renamed", "name", "S4 private name", "remark", "S4 private remark", "status", 0, "dataScope", "ALL", "sort", 7), trace);
        assertThat(node(changed).path("code").intValue()).isZero();
        JsonNode roleChange = byTrace(history(reader, "sys_role", role), trace, "sys_role");
        assertChange(roleChange, "code", code, code + "_renamed");
        assertChange(roleChange, "data_scope", "SELF", "ALL");
        assertChange(roleChange, "status", "1", "0");
        assertPersistedFields("sys_role", role, ROLE_FIELDS);
        List<Long> menus = jdbc.queryForList("SELECT id FROM sys_menu WHERE NOT deleted AND NOT admin_only AND perms IS NOT NULL ORDER BY id LIMIT 2", Long.class);
        assertThat(menus).hasSize(2);
        ok(admin, "PUT", "/api/roles/" + role + "/menus", map("ids", menus), null);
        ok(admin, "PUT", "/api/roles/" + role + "/menus", map("ids", List.of(menus.get(1))), null);
        JsonNode roleHistory = history(reader, "sys_role", role);
        var relationship = roleHistory.valueStream().filter(row -> row.path("table").asText().equals("sys_role_menu")).toList();
        assertThat(relationship).hasSize(5); // 两次真实批量替换：2 insert、2 delete、1 insert。
        for (JsonNode row : relationship) assertRelationship(row, "sys_role", role, "role_id", role, "menu_id");

        long largeUser = 9_007_199_254_740_993L + Long.parseLong(suffix().substring(0, 9), 16);
        String username = "s4_" + suffix();
        jdbc.update("INSERT INTO sys_user(id,username,password,nickname,default_company_id) OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?)",
                largeUser, username, passwords.encode("S4-Synthetic-Login-9374"), "S4 large ID", company);
        ok(admin, "PUT", "/api/users/" + largeUser + "/roles", map("ids", List.of(role)), null);
        ok(admin, "PUT", "/api/users/" + largeUser + "/roles", map("ids", List.of()), null);
        JsonNode userHistory = history(reader, "sys_user", largeUser);
        var membership = userHistory.valueStream().filter(row -> row.path("table").asText().equals("sys_user_role")).toList();
        assertThat(membership).hasSize(2);
        for (JsonNode row : membership) {
            assertRelationship(row, "sys_user", largeUser, "user_id", largeUser, "role_id");
            assertThat(row.path("rowKey").path("role_id").asText()).isEqualTo(Long.toString(role));
        }
        assertThat(membership.stream().map(row -> row.path("action").asText()).toList()).containsExactly("DELETE", "INSERT");
        evidence("role-security-history.json", roleHistory);
        evidence("membership-string-identity.json", userHistory);
    }

    @Test void failedActualGrantRollsBackBothMembershipAndFieldAudit() throws Exception {
        long company = company();
        Account admin = role("admin", company), reader = custom(company, "ALL", "system:audit:query");
        Account target = custom(company, "SELF");
        long replacement = id(ok(admin, "POST", "/api/roles", map("code", "s4_" + suffix(), "name", "S4 grant failure"), null));
        int before = auditCount("sys_user", target.id());
        String marker = "zz_s4_reject_" + suffix();
        jdbc.execute("CREATE FUNCTION " + marker + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id=" + target.id()
                + " AND NEW.role_id=" + replacement + " THEN RAISE EXCEPTION 'S4 private grant rejection'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " AFTER INSERT ON sys_user_role FOR EACH ROW EXECUTE FUNCTION " + marker + "()");
        String trace = "s4-grant-rollback-" + UUID.randomUUID();
        try {
            var failed = traced(admin, "PUT", "/api/users/" + target.id() + "/roles", map("ids", List.of(replacement)), trace);
            assertThat(node(failed).path("code").intValue()).isEqualTo(500);
            assertThat(node(failed).toString()).doesNotContain("S4 private grant rejection", "sys_user_role", "PSQLException", "stackTrace");
            assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?", Long.class, target.id())).containsExactly(target.role());
            assertThat(auditCount("sys_user", target.id())).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, trace)).isZero();
            assertThat(jdbc.queryForObject("SELECT success FROM sys_oper_log WHERE trace_id=?", Boolean.class, trace)).isFalse();
            evidence("failed-grant-response.json", node(failed));
            evidence("rollback-preserved-membership.json", history(reader, "sys_user", target.id()));
        } finally {
            jdbc.execute("DROP TRIGGER " + marker + " ON sys_user_role");
            jdbc.execute("DROP FUNCTION " + marker + "()");
        }
    }

    @Test void identityConstraintsRejectMalformedRelationshipsAndRepeatableDdlRunsTwice() throws Exception {
        String namespace = jdbc.queryForObject("SELECT tenant_id FROM sys_deployment_namespace WHERE singleton", String.class);
        JsonNode sharedBefore = auditDefinitions(jdbc);
        assertFixedWrapperPaths(sharedBefore);
        String isolatedDatabase;
        JsonNode isolatedBefore;
        List<JsonNode> replayed = new ArrayList<>();
        // Old CREATE OR REPLACE resets function SET options. Replaying it in the application
        // database would undo the newer audit wrapper configuration without changing Flyway history.
        // Use the existing profile-only fresh-database fixture; the published script stays untouched.
        try (var migration = IsolatedRoleMigration.migrate(dataSource, databasePassword, "59")) {
            JdbcTemplate isolated = migration.jdbc();
            isolatedDatabase = isolated.queryForObject("SELECT current_database()", String.class);
            assertThat(isolatedDatabase).isNotEqualTo(jdbc.queryForObject("SELECT current_database()", String.class));
            isolated.update("INSERT INTO sys_deployment_namespace(singleton,tenant_id) VALUES(true,?)", namespace);
            isolatedBefore = auditDefinitions(isolated);
            assertFixedWrapperPaths(isolatedBefore);
            try (var source = getClass().getResourceAsStream("/db/migration/R__security_field_audit.sql")) {
                assertThat(source).isNotNull();
                String ddl = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                // Keep the real short metadata COMMIT followed by the separate validation transaction.
                for (int run = 0; run < 2; run++) {
                    isolated.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                        assertThat(connection.getAutoCommit()).isTrue();
                        try (var statement = connection.createStatement()) { statement.execute(ddl); }
                        return null;
                    });
                    JsonNode actual = auditDefinitions(isolated);
                    replayed.add(actual);
                    assertThat(actual.path("functions").path("scm_security_audit_link_valid").isNull()).isTrue();
                    assertThat(actual.path("functions").path("scm_sample_audit_identity_valid"))
                            .isEqualTo(isolatedBefore.path("functions").path("scm_sample_audit_identity_valid"));
                    assertThat(actual.path("rowIdentitySha256")).isEqualTo(isolatedBefore.path("rowIdentitySha256"));
                }
            }
            for (String key : List.of("{}", "[]", "{\"user_id\":\"1\"}", "{\"user_id\":1,\"role_id\":\"2\"}",
                    "{\"user_id\":\"1\",\"role_id\":\"2\",\"extra\":\"3\"}", "{\"user_id\":\"0\",\"role_id\":\"2\"}",
                    "{\"user_id\":null,\"role_id\":\"2\"}", "{\"user_id\":\"-1\",\"role_id\":\"2\"}",
                    "{\"user_id\":\"9223372036854775808\",\"role_id\":\"2\"}", "{\"user_id\":\"3\",\"role_id\":\"2\"}")) {
                assertThatThrownBy(() -> isolated.update("""
                    INSERT INTO sys_data_log(tenant_key,table_name,row_id,row_key,root_table,root_id,price_domain,action,diff,transaction_id)
                    VALUES(?,'sys_user_role',NULL,?::jsonb,'sys_user',1,'SECURITY','INSERT','{}',txid_current())
                    """, namespace, key)).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> isolated.update("""
                INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,transaction_id)
                VALUES(?,'bas_company',NULL,'bas_company',1,'COST','INSERT','{}',txid_current())
                """, namespace)).isInstanceOf(DataIntegrityViolationException.class);
            for (String statement : List.of("UPDATE sys_data_log SET row_key=row_key WHERE false", "DELETE FROM sys_data_log WHERE false", "TRUNCATE sys_data_log"))
                assertThatThrownBy(() -> isolated.execute(statement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(isolated.queryForList("SELECT convalidated FROM pg_constraint WHERE conrelid='sys_data_log'::regclass AND conname IN ('ck_data_log_row_identity','ck_data_log_price_domain_security')", Boolean.class))
                .hasSize(2).containsOnly(true);
        }
        JsonNode sharedAfter = auditDefinitions(jdbc);
        assertThat(sharedAfter).isEqualTo(sharedBefore);
        boolean isolatedRemoved = Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM pg_database WHERE datname=?)", Boolean.class, isolatedDatabase));
        assertThat(isolatedRemoved).isTrue();
        evidence("security-repeatable-isolation.json", json.valueToTree(Map.of(
                "sharedBefore", sharedBefore, "isolatedDatabase", isolatedDatabase,
                "isolatedBefore", isolatedBefore, "isolatedAfterEachReplay", replayed,
                "sharedAfter", sharedAfter, "isolatedDatabaseRemoved", isolatedRemoved)));
    }

    private JsonNode auditDefinitions(JdbcTemplate source) throws Exception {
        JsonNode functions = json.readTree(source.queryForObject("""
                SELECT jsonb_object_agg(proname,to_jsonb(proconfig)) FROM pg_catalog.pg_proc
                WHERE oid IN ('public.scm_security_audit_link_valid(text,jsonb,text,bigint)'::regprocedure,
                              'public.scm_sample_audit_identity_valid(text,text,bigint,jsonb,text,bigint)'::regprocedure)
                """, String.class));
        String check = source.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='sys_data_log'::regclass AND conname='ck_data_log_row_identity'", String.class);
        assertThat(check).isNotNull();
        return json.valueToTree(Map.of("functions", functions, "rowIdentitySha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(check.getBytes(StandardCharsets.UTF_8)))));
    }

    private static void assertFixedWrapperPaths(JsonNode definitions) {
        for (String name : List.of("scm_security_audit_link_valid", "scm_sample_audit_identity_valid")) {
            JsonNode configuration = definitions.path("functions").path(name);
            assertThat(configuration.isArray()).as(name).isTrue();
            assertThat(configuration.size()).as(name).isOne();
            assertThat(configuration.get(0).asText()).as(name).isEqualTo("search_path=pg_catalog, public");
        }
    }

    @Test void committedMetadataPhaseAllowsRealConcurrentWritesDuringConstraintValidation() throws Exception {
        String constraint = "s4_validate_" + suffix();
        jdbc.execute("ALTER TABLE sys_data_log ADD CONSTRAINT " + constraint + " CHECK (id > 0) NOT VALID");
        try (var validator = dataSource.getConnection()) {
            validator.setAutoCommit(false);
            try {
                int pid;
                try (var query = validator.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(row.next()).isTrue(); pid = row.getInt(1);
                }
                // PostgreSQL keeps the genuine VALIDATE lock until this transaction commits.
                try (var statement = validator.createStatement()) { statement.execute("ALTER TABLE sys_data_log VALIDATE CONSTRAINT " + constraint); }
                List<String> locks = jdbc.queryForList("SELECT mode FROM pg_locks WHERE pid=? AND relation='sys_data_log'::regclass AND granted", String.class, pid);
                assertThat(locks).contains("ShareUpdateExclusiveLock").doesNotContain("AccessExclusiveLock");
                String code = "s4_" + suffix();
                long role = jdbc.queryForObject("INSERT INTO sys_role(code,name) VALUES(?,?) RETURNING id", Long.class, code, "S4 validation overlap");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name='sys_role' AND row_id=?", Integer.class, role)).isOne();
                // The validator has not committed; success proves a second real transaction wrote through its retained lock.
                assertThat(jdbc.queryForObject("SELECT state FROM pg_stat_activity WHERE pid=?", String.class, pid)).isEqualTo("idle in transaction");
                evidence("validation-lock-overlap.json", json.valueToTree(Map.of("validationTransactionStillOpen", true,
                        "locks", locks, "concurrentBusinessInsertAndAuditCommitted", true)));
            } finally { validator.rollback(); }
        } finally { jdbc.execute("ALTER TABLE sys_data_log DROP CONSTRAINT " + constraint); }
    }

    private long company() {
        return jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,'S4') RETURNING id", Long.class,
                suffix().substring(0, 6).toUpperCase(java.util.Locale.ROOT), "S4 audit fixture");
    }
    private JsonNode history(Account reader, String table, long id) throws Exception {
        return ok(reader, "GET", "/api/data-logs?rootTable=" + table + "&rootId=" + id + "&limit=100", null, null);
    }
    private JsonNode byTrace(JsonNode rows, String trace, String table) {
        return rows.valueStream().filter(row -> row.path("requestId").asText().equals(trace) && row.path("table").asText().equals(table)).findFirst().orElseThrow();
    }
    private int auditCount(String table, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table=? AND root_id=?", Integer.class, table, id);
    }
    private void assertPersistedFields(String table, long id, Set<String> allowed) {
        List<String> fields = jdbc.queryForList("SELECT DISTINCT jsonb_object_keys(diff) FROM sys_data_log WHERE table_name=? AND row_id=?", String.class, table, id);
        assertThat(fields).isNotEmpty().allMatch(allowed::contains);
    }
    private void assertRelationship(JsonNode row, String root, long rootId, String key, long id, String otherKey) {
        assertThat(row.path("rowId").isNull()).isTrue();
        assertThat(row.path("rootTable").asText()).isEqualTo(root);
        assertThat(row.path("rootId").asText()).isEqualTo(Long.toString(rootId));
        assertThat(row.path("rowKey").properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(key, otherKey);
        assertThat(row.path("rowKey").path(key).isString()).isTrue();
        assertThat(row.path("rowKey").path(key).asText()).isEqualTo(Long.toString(id));
        assertThat(row.path("rowKey").path(otherKey).isString()).isTrue();
        for (var field : row.path("diff").properties())
            for (String side : List.of("old", "new")) if (!field.getValue().path(side).isNull())
                assertThat(field.getValue().path(side).isString()).isTrue();
    }
    private static void assertChange(JsonNode row, String field, String old, String next) {
        assertThat(row.path("diff").path(field).path("old").asText()).isEqualTo(old);
        assertThat(row.path("diff").path(field).path("new").asText()).isEqualTo(next);
    }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, String trace) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json").header("X-Request-Id", trace)
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
    private void evidence(String filename, JsonNode value) throws Exception {
        Path path = Path.of("test-artifacts/full-audit/s4", filename);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
