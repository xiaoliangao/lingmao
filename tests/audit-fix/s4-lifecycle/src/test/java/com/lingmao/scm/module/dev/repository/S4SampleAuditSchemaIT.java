package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.security.DataScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 真正 Repository/PG 的字段事实与约束；HTTP 授权、报文及业务日志在独立 S4SampleAuditHttpIT。 */
@SpringBootTest
@ActiveProfiles("it")
@Import(IsolatedSampleSource.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = S4SampleAuditSchemaIT.SourceLifecycle.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class S4SampleAuditSchemaIT extends SampleTestSupport {
    @Autowired Flyway flyway;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    @Autowired IsolatedSampleSource isolatedSource;
    private static final String SOURCE_NAMESPACE = "s4-source-" + java.util.UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolateCacheNamespace(DynamicPropertyRegistry properties) {
        properties.add("lingmao.tenant-id", () -> SOURCE_NAMESPACE);
    }

    static class SourceLifecycle extends AbstractTestExecutionListener {
        private static final String SOURCE = SourceLifecycle.class.getName() + ".source";
        private static final String FAILURE = SourceLifecycle.class.getName() + ".failure";

        // after callbacks run in reverse order. DirtiesContext (3000) closes this
        // class's context first; no listener sees a manually closed cached context.
        @Override public int getOrder() { return 2500; }
        @Override public void beforeTestClass(TestContext context) {
            context.setAttribute(SOURCE, context.getApplicationContext().getBean(IsolatedSampleSource.class));
        }
        @Override public void afterTestMethod(TestContext context) {
            if (context.getTestException() != null && context.getAttribute(FAILURE) == null) {
                context.setAttribute(FAILURE, context.getTestException());
            }
        }
        @Override public void afterTestClass(TestContext context) {
            IsolatedSampleSource source = (IsolatedSampleSource) context.removeAttribute(SOURCE);
            Throwable primary = (Throwable) context.removeAttribute(FAILURE);
            if (source == null) return; // Failed initialization already invokes the bean's cleanup.
            try { source.requireRemoved(); }
            catch (RuntimeException cleanup) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw new AssertionError("Dedicated S4 source cleanup failed; inspect source-isolation.json", cleanup);
            }
        }
    }
    private static final Map<String, Set<String>> KEYS = Map.of(
            "dev_sample_color", Set.of("tenant_key", "company_id", "sample_id", "color_id"),
            "dev_sample_order_color", Set.of("tenant_key", "company_id", "sample_order_id", "color_id"),
            "dev_sample_order_size", Set.of("tenant_key", "company_id", "sample_order_id", "size_code"),
            "dev_sample_reviewer", Set.of("tenant_key", "company_id", "review_id", "user_id"));
    private static final Set<String> TABLES = Set.of("dev_sample", "dev_sample_color", "dev_sample_order",
            "dev_sample_order_color", "dev_sample_order_size", "dev_sample_node", "dev_sample_review", "dev_sample_reviewer");

    @Test void allEightTablesCaptureRealFactsAndTrueCompoundKeysBeforeAnyResponseProjection() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
            locked.sample().setEstCost(new BigDecimal("12.1234"));
            locked.sample().setFabricDesc("S4 private fabric description");
            locked.sample().setCustomerStyleNo("S4 private customer style");
            locked.sample().setRemark("S4 private sample note");
            samples.updateSample(locked, locked.sample());
        });
        dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        review(f, made.sampleId(), SampleReviewResult.PASS);
        List<Stored> rows = stored(made.sampleId());
        assertThat(rows.stream().map(Stored::table).collect(java.util.stream.Collectors.toSet())).isEqualTo(TABLES);
        assertThat(json.writeValueAsString(rows)).doesNotContain("S4 private", "S1 评审意见", "fabric_desc", "customer_style_no", "opinion", "remark", "recorded_txid");
        for (Stored row : rows) {
            assertThat(row.namespace()).isEqualTo(namespace.tenant()); assertThat(row.rootId()).isEqualTo(Long.toString(made.sampleId()));
            assertThat(row.priceDomain()).isEqualTo("COST");
            if (KEYS.containsKey(row.table())) {
                assertThat(row.rowId()).isNull(); assertThat(row.rowKey().isObject()).isTrue();
                assertThat(row.rowKey().propertyNames()).containsExactlyInAnyOrderElementsOf(KEYS.get(row.table()));
                row.rowKey().properties().forEach(entry -> assertThat(entry.getValue().isString()).as(entry.getKey()).isTrue());
                assertThat(row.rowKey().path("tenant_key").stringValue()).isEqualTo(row.namespace());
                assertThat(row.rowKey().path("company_id").stringValue()).isEqualTo(Long.toString(f.company()));
            } else {
                assertThat(row.rowId()).matches("[1-9][0-9]*"); assertThat(row.rowKey()).isNull();
            }
            for (var field : row.diff().properties()) if (field.getKey().equals("id") || field.getKey().equals("deleted_token")
                    || field.getKey().endsWith("_id") || field.getKey().endsWith("_by")) {
                for (String side : List.of("old", "new")) {
                    JsonNode value = field.getValue().path(side);
                    if (!value.isNull()) assertThat(value.isString()).as(row.table() + "." + field.getKey()).isTrue();
                }
            }
        }
        Stored cost = rows.stream().filter(row -> row.table().equals("dev_sample") && row.diff().has("est_cost")
                && row.diff().path("est_cost").path("new").isNumber()).findFirst().orElseThrow();
        assertThat(cost.diff().path("est_cost").path("new").decimalValue()).isEqualByComparingTo("12.1234");
        var review = jdbc.queryForMap("SELECT id::text AS id,recorded_txid::text AS transaction_id FROM dev_sample_review WHERE sample_order_id=?", made.orderId());
        assertThat(rows.stream().filter(row -> row.table().equals("dev_sample_review") || row.table().equals("dev_sample_reviewer"))
                .map(Stored::transactionId).toList()).containsOnly((String) review.get("transaction_id"));
        evidence("eight-tables-stored-facts.json", Map.of("rows", rows, "actualReview", review));
    }

    @Test void malformedCompositeKeysWrongNamespacesAndUnrelatedRealRootsAreRejectedWithoutWeakeningLegacyIdentity() throws Exception {
        Fixture f = fixture(); Created made = draft(f), other = draft(f);
        dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId()); review(f, made.sampleId(), SampleReviewResult.PASS);
        long review = jdbc.queryForObject("SELECT id FROM dev_sample_review WHERE sample_order_id=?", Long.class, made.orderId());
        long node = jdbc.queryForObject("SELECT min(id) FROM dev_sample_node WHERE sample_order_id=?", Long.class, made.orderId());
        List<Attempt> attempts = new ArrayList<>();
        for (String table : KEYS.keySet()) {
            ObjectNode key = key(table, f, made, review);
            attempts.add(attempt(table, made.sampleId(), key, made.sampleId(), namespace.tenant(), "COST", "fake scalar identity"));
            attempts.add(attempt(table, null, null, made.sampleId(), namespace.tenant(), "COST", "missing compound key"));
            ObjectNode missing = key(table, f, made, review); missing.remove("company_id");
            attempts.add(attempt(table, null, missing, made.sampleId(), namespace.tenant(), "COST", "missing PK field"));
            ObjectNode extra = key(table, f, made, review); extra.put("invented_id", "1");
            attempts.add(attempt(table, null, extra, made.sampleId(), namespace.tenant(), "COST", "extra PK field"));
            ObjectNode numeric = key(table, f, made, review); numeric.put("company_id", f.company());
            attempts.add(attempt(table, null, numeric, made.sampleId(), namespace.tenant(), "COST", "JSON number identity"));
            ObjectNode foreign = key(table, f, made, review); foreign.put("tenant_key", "sample_audit_foreign_fixture");
            attempts.add(attempt(table, null, foreign, made.sampleId(), namespace.tenant(), "COST", "key/event tenant mismatch"));
            attempts.add(attempt(table, null, foreign, made.sampleId(), "sample_audit_foreign_fixture", "COST", "forged deployment namespace"));
            attempts.add(attempt(table, null, key, other.sampleId(), namespace.tenant(), "COST", "unrelated real sample root"));
            attempts.add(attempt(table, null, key, made.sampleId(), namespace.tenant(), "ORDER", "wrong price domain"));
            for (String invalid : List.of("0", "-1", "9223372036854775808", "1.5")) {
                ObjectNode invalidId = key(table, f, made, review); invalidId.put("company_id", invalid);
                attempts.add(attempt(table, null, invalidId, made.sampleId(), namespace.tenant(), "COST", "invalid 64-bit identity " + invalid));
            }
        }
        ObjectNode noSize = key("dev_sample_order_size", f, made, review); noSize.putNull("size_code");
        attempts.add(attempt("dev_sample_order_size", null, noSize, made.sampleId(), namespace.tenant(), "COST", "null size"));
        ObjectNode oversized = key("dev_sample_order_size", f, made, review); oversized.put("size_code", "x".repeat(17));
        attempts.add(attempt("dev_sample_order_size", null, oversized, made.sampleId(), namespace.tenant(), "COST", "oversized size"));
        for (var entity : Map.of("dev_sample", made.sampleId(), "dev_sample_order", made.orderId(), "dev_sample_node", node, "dev_sample_review", review).entrySet()) {
            attempts.add(attempt(entity.getKey(), entity.getValue(), null, other.sampleId(), namespace.tenant(), "COST", "wrong entity root"));
            attempts.add(attempt(entity.getKey(), Long.MAX_VALUE, null, made.sampleId(), namespace.tenant(), "COST", "missing real entity"));
        }
        int before = auditCount(made.sampleId());
        List<Map<String, Object>> results = new ArrayList<>();
        for (Attempt attempt : attempts) {
            Throwable failed = catchThrowable(() -> jdbc.update("""
                    INSERT INTO sys_data_log(tenant_key,table_name,row_id,row_key,root_table,root_id,price_domain,action,diff,transaction_id)
                    VALUES(?,?,?,?::jsonb,'dev_sample',?,?,'INSERT','{}',txid_current())
                    """, attempt.namespace(), attempt.table(), attempt.rowId(), attempt.rowKey(), attempt.rootId(), attempt.domain()));
            assertThat(failed).as(attempt.label()).isNotNull();
            assertThat(sqlState(failed)).as(attempt.table() + ": " + attempt.label()).isEqualTo("23514");
            results.add(Map.of("attempt", attempt, "sqlState", sqlState(failed)));
        }
        assertThat(auditCount(made.sampleId())).isEqualTo(before);
        rejected("23514", () -> jdbc.update("""
                INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,transaction_id)
                VALUES(?,'bas_company',NULL,'bas_company',?,'COST','INSERT','{}',txid_current())
                """, namespace.tenant(), f.company()));
        rejected("23514", () -> jdbc.update("""
                INSERT INTO sys_data_log(tenant_key,table_name,row_id,row_key,root_table,root_id,price_domain,action,diff,transaction_id)
                VALUES(?,'sys_user_role',NULL,'{"user_id":"1","role_id":"2","extra":"3"}','sys_user',1,'SECURITY','INSERT','{}',txid_current())
                """, namespace.tenant()));
        for (String mutation : List.of("UPDATE sys_data_log SET row_key=row_key WHERE false", "DELETE FROM sys_data_log WHERE false", "TRUNCATE sys_data_log")) {
            Throwable failure = catchThrowable(() -> jdbc.execute(mutation));
            assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
            results.add(Map.of("appendOnlyAttempt", mutation, "sqlState", sqlState(failure)));
        }
        evidence("invalid-identities-and-append-only.json", Map.of("attempts", results, "auditRowsBefore", before, "auditRowsAfter", auditCount(made.sampleId())));
    }

    @Test void draftCompanyMoveKeepsOldKeysAndRollbackAndBothRepeatablesPreserveRealHistory() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        List<Stored> before = stored(made.sampleId()); String trace = unique("s4-sample-company-");
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('scm.audit_request',?,true)", String.class, trace);
            var locked = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
            locked.sample().setCompanyId(f.otherCompany()); samples.updateSample(locked, locked.sample());
        });
        List<Stored> moved = stored(made.sampleId());
        List<Stored> changed = moved.stream().filter(row -> row.diff().has("company_id") && row.action().equals("UPDATE")).toList();
        assertThat(changed.stream().map(Stored::table).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("dev_sample", "dev_sample_order", "dev_sample_color", "dev_sample_order_color", "dev_sample_order_size");
        for (Stored row : changed) {
            assertThat(row.diff().path("company_id").path("old").stringValue()).isEqualTo(Long.toString(f.company()));
            assertThat(row.diff().path("company_id").path("new").stringValue()).isEqualTo(Long.toString(f.otherCompany()));
            assertThat(row.requestId()).isEqualTo(trace);
            if (row.rowKey() != null) assertThat(row.rowKey().path("company_id").stringValue()).isEqualTo(Long.toString(f.otherCompany()));
        }
        assertThat(moved.stream().filter(row -> row.action().equals("INSERT") && row.rowKey() != null)
                .map(row -> row.rowKey().path("company_id").stringValue()).toList()).containsOnly(Long.toString(f.company()));
        int beforeRollback = auditCount(made.sampleId());
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.otherCompany(), made.sampleId(), DataScope.current(false), 1, null);
            locked.sample().setEstCost(new BigDecimal("99.9999")); samples.updateSample(locked, locked.sample());
            status.setRollbackOnly();
        });
        assertThat(auditCount(made.sampleId())).isEqualTo(beforeRollback);
        assertThat(jdbc.queryForObject("SELECT est_cost FROM dev_sample WHERE id=?", BigDecimal.class, made.sampleId())).isNull();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        for (String script : List.of("R__security_field_audit.sql", "R__zz_sample_field_audit.sql", "R__zz_sample_field_audit.sql")) {
            try (var resource = getClass().getResourceAsStream("/db/migration/" + script)) {
                assertThat(resource).isNotNull(); String ddl = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    assertThat(connection.getAutoCommit()).isTrue();
                    try (var statement = connection.createStatement()) { statement.execute(ddl); }
                    return null;
                });
            }
        }
        assertThat(stored(made.sampleId())).isEqualTo(moved);
        assertThat(jdbc.queryForList("SELECT convalidated FROM pg_constraint WHERE conrelid='sys_data_log'::regclass AND conname IN ('ck_data_log_row_identity','ck_data_log_price_domain_security')", Boolean.class))
                .hasSize(2).containsOnly(true);
        assertThat(jdbc.queryForList("SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE t.tgname='sample_field_audit' AND NOT t.tgisinternal", String.class))
                .containsExactlyInAnyOrderElementsOf(TABLES);
        evidence("company-history-repeatable-rollback.json", Map.of("before", before, "after", moved, "companyOld", Long.toString(f.company()),
                "companyNew", Long.toString(f.otherCompany()), "rollbackRowsBefore", beforeRollback, "rollbackRowsAfter", auditCount(made.sampleId()),
                "finalActualCostIsNull", jdbc.queryForObject("SELECT est_cost IS NULL FROM dev_sample WHERE id=?", Boolean.class, made.sampleId())));
    }

    @Test void realLargeSampleIdentityIsAStringInEntityDiffAndAllCompositeKeys() throws Exception {
        Fixture f = fixture(); login(f.creator(), true);
        long large = 9_007_199_254_740_993L + Math.floorMod(java.util.UUID.randomUUID().getLeastSignificantBits(), 1_000_000L);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO dev_sample(id,tenant_key,company_id,code,name,brand,category_id,year,season,source,size_group_id,
                        designer_id,pattern_maker_id,create_by,est_tag_price,est_cost)
                    OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,'S4 real long identity','S1_BRAND',?,2026,'SPRING','OWN',?,?,?,?,'999999999999.99','1234567890.1234')
                    """, large, namespace.tenant(), f.company(), unique("S4BIG"), f.category(), f.sizeGroup(), f.designer(), f.maker(), f.creator());
            var sample = sample(f); sample.setId(large); samples.replaceColors(sample, f.colors().stream().map(SampleRepository.ColorChoice::id).toList());
            var order = order(f, large, 1); samples.insertOrder(order); samples.replaceSelection(order, List.of(f.colors().getFirst()), List.of("M"));
        });
        List<Stored> rows = stored(large);
        assertThat(rows).isNotEmpty(); assertThat(rows.stream().map(Stored::rootId)).containsOnly(Long.toString(large));
        Stored root = rows.stream().filter(row -> row.table().equals("dev_sample")).findFirst().orElseThrow();
        assertThat(root.rowId()).isEqualTo(Long.toString(large));
        assertThat(root.diff().path("id").path("new").isString()).isTrue();
        assertThat(root.diff().path("id").path("new").stringValue()).isEqualTo(Long.toString(large));
        assertThat(rows.stream().filter(row -> row.table().equals("dev_sample_color"))
                .map(row -> row.rowKey().path("sample_id").stringValue())).containsOnly(Long.toString(large));
        assertThat(root.diff().path("est_cost").path("new").decimalValue()).isEqualByComparingTo("1234567890.1234");
        evidence("large-real-identities.json", rows);
    }

    @Test void actualPgDumpRestorePreservesAllEightTablesCompanyHistoryAndActiveAuditGuards() throws Exception {
        Fixture f = fixture(); Created complete = draft(f), moved = draft(f);
        dispatch(f, complete.sampleId(), MakeWay.INTERNAL); send(f, complete.sampleId());
        review(f, complete.sampleId(), SampleReviewResult.PASS);
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.company(), moved.sampleId(), DataScope.current(false), 0, null);
            locked.sample().setCompanyId(f.otherCompany()); samples.updateSample(locked, locked.sample());
        });
        // Restore must also exercise the existing SECURITY wrapper using real membership events.
        long role = jdbc.queryForObject("INSERT INTO sys_role(code,name) VALUES(?,?) RETURNING id", Long.class,
                unique("S4RESTORE"), "S4 合成恢复角色");
        long menu = jdbc.queryForObject("SELECT id FROM sys_menu WHERE perms='dev:sample:list' AND NOT deleted", Long.class);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", f.creator(), role);
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)", role, menu);
        Path output = Path.of("test-artifacts/full-audit/sample/s4-schema/dump-restore.json");
        Files.createDirectories(output.getParent());
        ProcessBuilder command = new ProcessBuilder("python3", "tests/audit-fix/s4-lifecycle/sample-audit-dump-restore.py",
                "--sample-id", Long.toString(complete.sampleId()), "--sample-id", Long.toString(moved.sampleId()),
                "--security-role-id", Long.toString(role), "--output", output.toString());
        try (var connection = dataSource.getConnection()) {
            command.environment().put("SCM_SAMPLE_TEST_JDBC_URL", connection.getMetaData().getURL());
            command.environment().put("SCM_SAMPLE_TEST_DB_USER", connection.getMetaData().getUserName());
        }
        command.environment().put("SCM_SAMPLE_TEST_DB_PASSWORD", environment.getProperty("spring.datasource.password", ""));
        SampleRestoreProcess.Result process = SampleRestoreProcess.run(command, output.resolveSibling("dump-restore-process.log"), json);
        assertThat(process.finished()).as("real dump/restore helper completed, no missing-tool skip; %s", process.safeSummary()).isTrue();
        assertThat(process.exitCode()).as("safe dump/restore process and JSON evidence; %s", process.safeSummary()).isZero();
        assertThat(process.diagnosticsComplete()).as("bounded pipe diagnostics were completely consumed and persisted; %s", process.safeSummary()).isTrue();
        JsonNode result = json.readTree(Files.readString(output));
        assertThat(result.path("sourceDatabase").stringValue()).isEqualTo(isolatedSource.database())
                .isNotEqualTo(isolatedSource.sharedDatabase());
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("privateArchiveRemoved").asBoolean()).isTrue();
        assertThat(result.path("targetDatabaseRemoved").asBoolean()).isTrue();
        assertThat(result.path("sourceSnapshotSha256").stringValue()).isEqualTo(result.path("restoredSnapshotSha256").stringValue());
        assertThat(result.path("forgedRootSqlState").stringValue()).isEqualTo("23514");
        assertThat(result.path("emptySearchPathProbe").path("searchPath").stringValue()).isEmpty();
        assertThat(result.path("emptySearchPathProbe").path("sampleValid").asBoolean()).isTrue();
        assertThat(result.path("emptySearchPathProbe").path("securityRows").asInt()).isEqualTo(2);
        assertThat(result.path("emptySearchPathProbe").path("securityValid").asBoolean()).isTrue();
    }

    private record Stored(String id, String namespace, String table, String rowId, JsonNode rowKey, String rootId,
                          String priceDomain, String action, JsonNode diff, String requestId, String transactionId) { }
    private List<Stored> stored(long sample) {
        return jdbc.query("SELECT * FROM sys_data_log WHERE tenant_key=? AND root_table='dev_sample' AND root_id=? ORDER BY id",
                (rs, n) -> new Stored(rs.getString("id"), rs.getString("tenant_key"), rs.getString("table_name"), rs.getString("row_id"),
                        rs.getString("row_key") == null ? null : json.readTree(rs.getString("row_key")), rs.getString("root_id"), rs.getString("price_domain"),
                        rs.getString("action"), json.readTree(rs.getString("diff")), rs.getString("request_id"), rs.getString("transaction_id")), namespace.tenant(), sample);
    }
    private int auditCount(long sample) { return jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE tenant_key=? AND root_table='dev_sample' AND root_id=?", Integer.class, namespace.tenant(), sample); }
    private record Attempt(String table, Long rowId, String rowKey, long rootId, String namespace, String domain, String label) { }
    private Attempt attempt(String table, Long rowId, JsonNode key, long rootId, String tenant, String domain, String label) {
        return new Attempt(table, rowId, key == null ? null : json.writeValueAsString(key), rootId, tenant, domain, label);
    }
    private ObjectNode key(String table, Fixture f, Created made, long review) {
        ObjectNode key = json.createObjectNode(); key.put("tenant_key", namespace.tenant()); key.put("company_id", Long.toString(f.company()));
        switch (table) {
            case "dev_sample_color" -> { key.put("sample_id", Long.toString(made.sampleId())); key.put("color_id", Long.toString(f.colors().getFirst().id())); }
            case "dev_sample_order_color" -> { key.put("sample_order_id", Long.toString(made.orderId())); key.put("color_id", Long.toString(f.colors().getFirst().id())); }
            case "dev_sample_order_size" -> { key.put("sample_order_id", Long.toString(made.orderId())); key.put("size_code", "M"); }
            case "dev_sample_reviewer" -> { key.put("review_id", Long.toString(review)); key.put("user_id", Long.toString(f.creator())); }
            default -> throw new IllegalArgumentException("Unexpected fixture table");
        }
        return key;
    }
    @Override void evidence(String name, Object value) throws Exception {
        Path directory = Path.of("test-artifacts/full-audit/sample/s4-schema"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
