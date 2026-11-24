package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Runs the shipped psql entry point; calling the Java maintenance bean cannot verify this path. */
@IntegrationTest
class NoticeSummaryOperationsIT {
    private static final String TYPE = "PRODUCTION_ORDER";
    private static final int KEYS = 501;
    private static final Path SCRIPT = Path.of("deploy/package/notice-summary.sql").toAbsolutePath();
    private static final Path EVIDENCE = Path.of("test-artifacts/full-audit/s1/notice-summary");
    private static final List<String> STATUS_COLUMNS = List.of("tenant_key", "backfill_after_type", "backfill_after_id",
            "backfill_complete", "reconcile_after_type", "reconcile_after_id", "reconcile_complete",
            "reconcile_mismatches", "ready", "use_summary");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    @Autowired DeploymentNamespace namespace;
    @Autowired JsonMapper json;
    @TempDir Path temporary;

    private final long firstDoc = ThreadLocalRandom.current().nextLong(7_000_000_000_000L, 8_000_000_000_000L);
    private final List<Invocation> invocations = new ArrayList<>();
    private Client client;

    private record Client(Path executable, Map<String, String> environment) { }
    private record Invocation(String action, String batch, int exitCode, String stdout, String stderr,
                              Map<String, Object> committedState) { }

    @Test void shippedPsqlScriptPersistsBoundedProgressRejectsBadInputAndRepairsPhysicalSummary() throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.deleteIfExists(EVIDENCE.resolve("psql-operations.json"));
        Files.writeString(EVIDENCE.resolve("psql-invocations.jsonl"), "");
        client = guardedClient(); // Fail closed before fixture writes; no missing-client skip.
        assertThat(Files.isRegularFile(SCRIPT)).isTrue();
        Map<String, Object> saved = state();
        try (AutoCloseable cleanup = () -> cleanup(saved)) {
            Invocation initial = success(null, null); // The documented defaults select status and batch=500.
            assertThat(initial.committedState()).isEqualTo(saved);
            fixture();
            assertThat(ownSummaryCount()).isEqualTo(KEYS);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger WHERE tgrelid='biz_doc_notice'::regclass
                        AND tgname IN ('notice_pending_insert','notice_pending_update','notice_pending_delete')
                        AND NOT tgisinternal AND tgenabled IN ('O','A')
                    """, Long.class)).isEqualTo(3);
            deleteOwnSummaries(); // Simulate pre-V61 source rows; every source trigger remains enabled.
            assertThat(ownSummaryCount()).isZero();

            success("restart", null);
            assertThat(state()).containsEntry("backfill_after_type", null).containsEntry("backfill_after_id", null)
                    .containsEntry("backfill_complete", false).containsEntry("ready", false).containsEntry("use_summary", false);
            rejectUnchanged("enable", null);
            rejectUnchanged("check-start", null);
            rejectUnchanged("not-an-action", null);
            rejectUnchanged("restart'; SELECT scm_notice_pending_set_use_summary(true); --", null);
            rejectBadBatches("backfill");

            Map<String, Object> firstKey = jdbc.queryForMap("""
                    SELECT doc_type,doc_id FROM (
                        SELECT doc_type,doc_id FROM biz_doc_notice WHERE handled_time IS NULL
                        UNION SELECT doc_type,doc_id FROM biz_doc_notice_pending WHERE tenant_key=?) keys
                    ORDER BY doc_type,doc_id LIMIT 1
                    """, namespace.tenant());
            progress(success("backfill", "1"), 1);
            Map<String, Object> paused = state();
            assertThat(paused).containsEntry("backfill_after_type", firstKey.get("doc_type"))
                    .containsEntry("backfill_after_id", firstKey.get("doc_id")).containsEntry("backfill_complete", false);
            assertThat(success("status", null).committedState()).isEqualTo(paused);
            assertThat(progress(success("backfill", null), 500)).isEqualTo(500); // At least 501 fixture keys, even in a shared DB.
            finish("backfill", "backfill_complete");
            assertPhysicalSummary();
            assertThat(state()).containsEntry("backfill_complete", true).containsEntry("ready", false);
            rejectUnchanged("enable", null);

            success("check-start", null);
            rejectBadBatches("check");
            progress(success("check", "1"), 1);
            Map<String, Object> pausedCheck = state();
            assertThat(pausedCheck).containsEntry("reconcile_after_type", firstKey.get("doc_type"))
                    .containsEntry("reconcile_after_id", firstKey.get("doc_id")).containsEntry("reconcile_complete", false);
            assertThat(success("status", null).committedState()).isEqualTo(pausedCheck);
            finish("check", "reconcile_complete");
            assertThat(state()).containsEntry("reconcile_mismatches", 0L).containsEntry("ready", true);
            success("enable", null);
            assertThat(success("status", null).committedState()).containsEntry("use_summary", true);

            success("fallback", null);
            assertThat(state()).containsEntry("use_summary", false).containsEntry("ready", true);
            long revision = jdbc.queryForObject("""
                    SELECT revision FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=?
                    """, Long.class, namespace.tenant(), TYPE, firstDoc);
            long added = jdbc.queryForObject("""
                    INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time)
                    VALUES(?,?,'ORDER_CHANGE',?,'P33 psql fallback write','2026-02-01T00:00:00Z') RETURNING id
                    """, Long.class, TYPE, firstDoc, firstDoc);
            assertPhysicalSummary();
            assertThat(jdbc.update("DELETE FROM biz_doc_notice WHERE id=?", added)).isEqualTo(1);
            assertPhysicalSummary();
            assertThat(jdbc.queryForObject("""
                    SELECT revision FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=?
                    """, Long.class, namespace.tenant(), TYPE, firstDoc)).isEqualTo(revision + 2);

            assertThat(jdbc.update("""
                    UPDATE biz_doc_notice_pending SET pending_count=pending_count+1
                    WHERE tenant_key=? AND doc_type=? AND doc_id=?
                    """, namespace.tenant(), TYPE, firstDoc)).isEqualTo(1);
            success("check-start", null);
            finish("check", "reconcile_complete");
            assertThat(state()).containsEntry("reconcile_mismatches", 1L).containsEntry("ready", false);
            assertThat(physicalMismatches()).isEqualTo(1);
            rejectUnchanged("enable", null);
            success("restart", null);
            assertThat(state()).containsEntry("backfill_after_id", null).containsEntry("reconcile_after_id", null)
                    .containsEntry("ready", false).containsEntry("use_summary", false);
            finish("backfill", "backfill_complete");
            assertPhysicalSummary(); // Repair must change the actual derived values, not just the ready flag.
            success("check-start", null);
            finish("check", "reconcile_complete");
            assertThat(state()).containsEntry("reconcile_mismatches", 0L).containsEntry("ready", true);
            success("enable", null);
            assertThat(success("status", null).committedState()).containsEntry("use_summary", true);
        }
        assertThat(state()).isEqualTo(saved);
        assertThat(ownSummaryCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE source_type='ORDER_CHANGE' AND source_id=?",
                Long.class, firstDoc)).isZero();
        Files.writeString(EVIDENCE.resolve("psql-operations.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "passed", true, "script", "deploy/package/notice-summary.sql", "fixtureKeys", KEYS, "cleanupVerified", true,
                "sourceTriggersEnabled", true, "physicalRepairVerified", true, "invocations", invocations)));
    }

    private void fixture() {
        assertThat(jdbc.update("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time)
                SELECT 'PRODUCTION_ORDER',?+n,'ORDER_CHANGE',?,'P33 psql legacy',
                       '2026-01-01T00:00:00Z'::timestamptz+n*interval '1 second'
                FROM generate_series(0,500) n
                """, firstDoc, firstDoc)).isEqualTo(KEYS);
        jdbc.update("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time,handled_time)
                VALUES(?,?,'ORDER_CHANGE',?,'P33 psql latest','2026-01-02T00:00:00Z',NULL),
                      (?,?,'ORDER_CHANGE',?,'P33 psql handled history','2026-01-03T00:00:00Z','2026-01-04T00:00:00Z')
                """, TYPE, firstDoc, firstDoc, TYPE, firstDoc, firstDoc);
    }

    private void finish(String action, String completeColumn) throws Exception {
        long keys = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT doc_type,doc_id FROM biz_doc_notice WHERE handled_time IS NULL
                    UNION SELECT doc_type,doc_id FROM biz_doc_notice_pending WHERE tenant_key=?) keys
                """, Long.class, namespace.tenant());
        for (long batches = keys / 500 + 2; batches > 0; batches--) {
            Invocation result = success(action, "500");
            progress(result, 500);
            if (Boolean.TRUE.equals(result.committedState().get(completeColumn))) return;
        }
        throw new AssertionError("psql maintenance exceeded the actual key-count batch bound");
    }

    private int progress(Invocation result, int limit) {
        List<String> lines = result.stdout().lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(2);
        String[] fields = lines.getFirst().split("\t", -1);
        assertThat(fields).hasSize(3);
        int processed = Integer.parseInt(fields[0]);
        assertThat(processed).isBetween(0, limit);
        String complete = result.action().equals("backfill") ? "backfill_complete" : "reconcile_complete";
        assertThat(fields[1]).isEqualTo(pgValue(result.committedState().get(complete)));
        assertThat(Long.parseLong(fields[2])).isEqualTo(((Number) result.committedState().get("reconcile_mismatches")).longValue());
        return processed;
    }

    private void rejectBadBatches(String action) throws Exception {
        for (String batch : List.of("0", "501", "not-an-integer", "1); SELECT scm_notice_pending_set_use_summary(true); --")) {
            rejectUnchanged(action, batch);
        }
    }

    private void rejectUnchanged(String action, String batch) throws Exception {
        Map<String, Object> before = state();
        String summaries = jdbc.queryForObject("""
                SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY doc_id),'[]'::jsonb)::text
                FROM biz_doc_notice_pending s WHERE tenant_key=? AND doc_type=? AND doc_id BETWEEN ? AND ?
                """, String.class, namespace.tenant(), TYPE, firstDoc, firstDoc + KEYS - 1);
        Invocation result = invoke(action, batch);
        assertThat(result.exitCode()).as("psql must reject this action/argument; see invocation evidence").isNotZero();
        assertThat(result.committedState()).isEqualTo(before); // Includes update_time; failures cannot even move the cursor.
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY doc_id),'[]'::jsonb)::text
                FROM biz_doc_notice_pending s WHERE tenant_key=? AND doc_type=? AND doc_id BETWEEN ? AND ?
                """, String.class, namespace.tenant(), TYPE, firstDoc, firstDoc + KEYS - 1)).isEqualTo(summaries);
    }

    private Invocation success(String action, String batch) throws Exception {
        Invocation result = invoke(action, batch);
        assertThat(result.exitCode()).as("psql action %s; stderr=%s", action, result.stderr()).isZero();
        String last = result.stdout().lines().filter(line -> !line.isBlank()).toList().getLast();
        String[] columns = last.split("\t", -1);
        assertThat(columns).hasSize(11);
        assertThat(Arrays.asList(columns).subList(0, 10)).containsExactlyElementsOf(
                STATUS_COLUMNS.stream().map(column -> pgValue(result.committedState().get(column))).toList());
        assertThat(columns[10]).isNotBlank();
        return result;
    }

    private Invocation invoke(String action, String batch) throws Exception {
        List<String> command = new ArrayList<>(List.of(client.executable().toString(), "-X", "-w", "-q", "-A", "-t",
                "-F", "\t", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate"));
        if (action != null) command.addAll(List.of("-v", "action=" + action));
        if (batch != null) command.addAll(List.of("-v", "batch=" + batch));
        command.addAll(List.of("-f", SCRIPT.toString()));
        Path stdout = temporary.resolve(invocations.size() + ".out"), stderr = temporary.resolve(invocations.size() + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("PG"));
        builder.environment().putAll(client.environment()); // Credentials never enter argv or the evidence.
        Process process = builder.start();
        try (AutoCloseable cleanup = () -> terminate(process)) {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("psql must finish within the process guard").isTrue();
            Invocation result = new Invocation(action == null ? "status" : action, batch == null ? "500" : batch,
                    process.exitValue(), Files.readString(stdout), Files.readString(stderr), state());
            invocations.add(result);
            Files.writeString(EVIDENCE.resolve("psql-invocations.jsonl"), json.writeValueAsString(result) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return result;
        }
    }

    private Client guardedClient() throws Exception {
        String url, user;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL(); user = connection.getMetaData().getUserName();
        }
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalStateException("A PostgreSQL integration-test URL is required");
        URI address = URI.create(url.substring("jdbc:".length()));
        String host = address.getHost(), database = address.getPath().substring(1);
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host) || address.getRawUserInfo() != null
                || !database.matches("[A-Za-z0-9_]+_test")
                || !database.equals(jdbc.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("Only the verified loopback integration-test database ending in _test is permitted");
        }
        String configured = System.getenv("SCM_TEST_PG_BIN");
        Path executable = configured == null ? Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .map(directory -> Path.of(directory).resolve("psql")).filter(Files::isRegularFile).filter(Files::isExecutable)
                .findFirst().orElseThrow(() -> new IllegalStateException("Required PostgreSQL client is unavailable: psql"))
                : Path.of(configured).resolve("psql");
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("Required PostgreSQL client is unavailable: psql");
        }
        Map<String, String> child = new HashMap<>();
        child.put("PGHOST", host.replace("[", "").replace("]", ""));
        child.put("PGPORT", Integer.toString(address.getPort() < 0 ? 5432 : address.getPort()));
        child.put("PGDATABASE", database); child.put("PGUSER", Objects.requireNonNull(user));
        child.put("PGPASSWORD", environment.getProperty("spring.datasource.password", ""));
        child.put("PGTZ", "UTC"); child.put("PGCONNECT_TIMEOUT", "5"); child.put("PGAPPNAME", "p33_notice_operations_it");
        return new Client(executable.toAbsolutePath(), child);
    }

    private Map<String, Object> state() {
        // Compare the native JDBC timestamp; also retain its full precision in JSON despite the global date formatter.
        return jdbc.queryForMap("""
                SELECT s.*,to_char(s.update_time AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS update_time_exact
                FROM biz_doc_notice_pending_state s WHERE tenant_key=?
                """, namespace.tenant());
    }

    private long ownSummaryCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id BETWEEN ? AND ?
                """, Long.class, namespace.tenant(), TYPE, firstDoc, firstDoc + KEYS - 1);
    }

    private long physicalMismatches() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT doc_type,doc_id,COUNT(*) AS count,MAX(create_time) AS latest
                    FROM biz_doc_notice WHERE source_type='ORDER_CHANGE' AND source_id=? AND handled_time IS NULL
                    GROUP BY doc_type,doc_id) expected
                LEFT JOIN biz_doc_notice_pending s ON s.tenant_key=? AND s.doc_type=expected.doc_type AND s.doc_id=expected.doc_id
                WHERE s.doc_id IS NULL OR NOT s.initialized OR s.pending_count IS DISTINCT FROM expected.count
                    OR s.latest_time IS DISTINCT FROM expected.latest OR s.latest_notice_id IS DISTINCT FROM (
                        SELECT n.id FROM biz_doc_notice n WHERE n.doc_type=expected.doc_type AND n.doc_id=expected.doc_id
                            AND n.handled_time IS NULL ORDER BY n.create_time DESC,n.id DESC LIMIT 1)
                """, Long.class, firstDoc, namespace.tenant());
    }

    private void assertPhysicalSummary() {
        assertThat(ownSummaryCount()).isEqualTo(KEYS);
        assertThat(physicalMismatches()).isZero();
    }

    private void deleteOwnSummaries() {
        jdbc.update("""
                DELETE FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id BETWEEN ? AND ?
                """, namespace.tenant(), TYPE, firstDoc, firstDoc + KEYS - 1);
    }

    private void cleanup(Map<String, Object> saved) {
        assertAll("psql fixture cleanup and saved operator state restoration",
            this::deleteOwnSummaries,
            () -> {
                while (jdbc.update("""
                        DELETE FROM biz_doc_notice WHERE id IN (SELECT id FROM biz_doc_notice
                            WHERE source_type='ORDER_CHANGE' AND source_id=? LIMIT 500)
                        """, firstDoc) > 0) { }
            },
            this::deleteOwnSummaries,
            () -> jdbc.update("""
                    UPDATE biz_doc_notice_pending_state SET backfill_after_type=?,backfill_after_id=?,backfill_complete=?,
                        reconcile_after_type=?,reconcile_after_id=?,reconcile_complete=?,reconcile_mismatches=?,
                        ready=?,use_summary=?,update_time=? WHERE tenant_key=?
                    """, saved.get("backfill_after_type"), saved.get("backfill_after_id"), saved.get("backfill_complete"),
                    saved.get("reconcile_after_type"), saved.get("reconcile_after_id"), saved.get("reconcile_complete"),
                    saved.get("reconcile_mismatches"), saved.get("ready"), saved.get("use_summary"), saved.get("update_time"), namespace.tenant()));
    }

    private static String pgValue(Object value) {
        return value instanceof Boolean bool ? bool ? "t" : "f" : Objects.toString(value, "");
    }

    private static void terminate(Process process) throws InterruptedException {
        if (!process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).as("psql process cleanup").isTrue();
        }
    }
}
