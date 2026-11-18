package com.lingmao.scm.config;

import com.lingmao.scm.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLWarning;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real replacement triggers at BOM scale; no disabled FK, audit trigger or planner method. */
@IntegrationTest
class A4AuditScaleIT {
    private static final int PARENTS = 10;
    private static final List<String> EXPLAIN_SETTINGS = List.of(
            "auto_explain.log_min_duration", "auto_explain.log_nested_statements", "auto_explain.log_analyze",
            "auto_explain.log_timing", "auto_explain.log_buffers", "auto_explain.log_format",
            "auto_explain.log_level", "client_min_messages");

    @Autowired DataSource dataSource;
    @Autowired JsonMapper json;

    @ParameterizedTest(name = "{0} real BOM SKU replacements keep identity lookups bounded")
    @ValueSource(ints = {500, 1000})
    void actualReplacementKeepsAllEventsAndUsesBoundedInternalPlans(int skuCount) throws Exception {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("passed", false);
        evidence.put("skuRows", skuCount);
        evidence.put("bomParents", PARENTS);
        evidence.put("dimensionCandidatesPerParent", skuCount / PARENTS);
        evidence.put("planPolicy", "Default planner and automatic plan cache; no timing threshold or disabled constraints");
        var nestedPlans = new ArrayList<JsonNode>();
        evidence.put("actualNestedPlans", nestedPlans);

        // One physical pool lease is essential: borrowing through ObservedDataSource
        // again would intentionally reset scm.audit_replacements to request context.
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "LOAD 'auto_explain'");
            Map<String, String> previousSettings = settings(connection);
            Fixture fixture = null;
            try {
                assertThat(text(connection, "SHOW session_replication_role")).isEqualTo("origin");
                assertThat(text(connection, "SHOW enable_seqscan")).isEqualTo("on");
                assertThat(text(connection, "SHOW plan_cache_mode")).isEqualTo("auto");
                connection.setAutoCommit(false);
                fixture = fixture(connection, skuCount);
                connection.commit();
                execute(connection, "ANALYZE sys_data_log");
                execute(connection, "ANALYZE tp_bom_item");
                execute(connection, "ANALYZE tp_bom_item_sku");

                var hints = new LinkedHashMap<String, String>();
                fixture.parents().forEach((seq, id) -> hints.put(Integer.toString(seq), Long.toString(id)));
                configure(connection, "scm.audit_replacements", json.writeValueAsString(Map.of(
                        "rootTable", "tp_tech_pack", "rootId", Long.toString(fixture.pack()),
                        "tables", Map.of("tp_bom_item", hints))), true);
                configure(connection, "scm.audit_request", "a4-scale-" + UUID.randomUUID(), true);
                configure(connection, "scm.audit_operation", "A4AuditScaleIT.replace", true);
                long transaction = number(connection, "SELECT txid_current()");
                evidence.put("rootId", Long.toString(fixture.pack()));
                evidence.put("transactionId", Long.toString(transaction));

                assertThat(execute(connection, """
                        DELETE FROM tp_bom_item_sku WHERE bom_item_id IN
                        (SELECT id FROM tp_bom_item WHERE tech_pack_id=?)
                        """, fixture.pack())).isEqualTo(skuCount);
                assertThat(execute(connection, "DELETE FROM tp_bom_item WHERE tech_pack_id=?", fixture.pack()))
                        .isEqualTo(PARENTS);
                // Reversed creation order proves that hints associate old identities by
                // supplied seq, rather than relying on allocation or insertion order.
                assertThat(execute(connection, """
                        INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_mode,usage_qty,unit,ref_price)
                        SELECT ?,n,'MAIN',?,'BY_SIZE','UNIFORM',22,'METER',12
                        FROM generate_series(10,1,-1) n
                        """, fixture.pack(), fixture.material())).isEqualTo(PARENTS);
                Map<Integer, Long> currentParents = parents(connection, fixture.pack());

                // EXPLAIN ANALYZE executes the real N-1 inserts. Repeated trigger SPI
                // execution gets its normal opportunity to choose generic plans.
                JsonNode bulk = explain(connection, """
                        INSERT INTO tp_bom_item_sku(bom_item_id,size_code,material_sku_id,ref_price)
                        SELECT p.id,'S'||lpad(n::text,4,'0'),?,12
                        FROM tp_bom_item p CROSS JOIN generate_series(1,?) n
                        WHERE p.tech_pack_id=? AND NOT(p.seq=10 AND n=?) ORDER BY p.seq,n
                        """, fixture.materialSku(), skuCount / PARENTS, fixture.pack(), skuCount / PARENTS);
                evidence.put("bulkInsertExplain", bulk);
                assertThat(bulk.path("Triggers").valueStream()
                        .filter(node -> node.path("Trigger Name").asText().equals("field_audit"))
                        .mapToLong(node -> node.path("Calls").longValue()).sum()).isEqualTo(skuCount - 1);

                captureFinalInsert(connection, currentParents.get(PARENTS), fixture.materialSku(),
                        size(skuCount / PARENTS), nestedPlans);
                Map<String, JsonNode> internal = replacementPlans(nestedPlans);
                evidence.put("internalPlanChecks", Map.of(
                        "parentReplacement", checkAccess(internal.get("parentReplacement"),
                                "idx_data_log_replaced_bom_parent", "row_id", 1, 1, true),
                        "deletedChild", checkAccess(internal.get("deletedChild"),
                                "idx_data_log_delete_bom_parent", "bom_item_id", 1, skuCount / PARENTS, true),
                        "previousIdentity", checkAccess(internal.get("previousIdentity"),
                                "idx_data_log_previous_identity", "previous_row_id", 0, 0, true),
                        "deletedIdentity", checkAccess(internal.get("deletedIdentity"),
                                "idx_data_log_deleted_identity", "row_id", 1, 1, true)));

                // The last inserted dimension often needs no residual filtering. That
                // sample does NOT prove every dimension is a point lookup: the earliest
                // size can examine all 50/100 children under its verified old parent.
                JsonNode oldest = explain(connection, """
                        SELECT row_id FROM sys_data_log
                        WHERE tenant_key=? AND root_table='tp_tech_pack' AND root_id=?
                          AND transaction_id=txid_current() AND table_name='tp_bom_item_sku' AND action='DELETE'
                          AND diff->'bom_item_id'->>'old'=?
                          AND (diff->'size_code'->'old') IS NOT DISTINCT FROM ?::jsonb
                          AND (diff->'color_id'->'old') IS NOT DISTINCT FROM 'null'::jsonb
                        ORDER BY id DESC LIMIT 1
                        """, fixture.tenant(), fixture.pack(), Long.toString(fixture.parents().get(PARENTS)),
                        json.writeValueAsString(size(1)));
                evidence.put("oldestDimensionExplain", oldest);
                evidence.put("oldestDimensionAccess", checkAccess(oldest, "idx_data_log_delete_bom_parent",
                        "bom_item_id", 1, skuCount / PARENTS, false));

                Map<String, Long> counts = counts(connection, fixture, transaction);
                assertThat(counts).isEqualTo(Map.of("DELETE", (long) skuCount + PARENTS,
                        "INSERT", (long) skuCount + PARENTS, "REPLACE", (long) skuCount + PARENTS));
                evidence.put("transactionAuditCounts", counts);
                assertIdentitiesAndDiffs(connection, fixture, currentParents, transaction, skuCount);
                assertThat(number(connection, """
                        SELECT count(*) FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id
                        WHERE b.tech_pack_id=? AND s.ref_price=12
                        """, fixture.pack())).isEqualTo(skuCount);
                assertThat(number(connection, """
                        SELECT count(*) FROM pg_trigger WHERE tgrelid IN
                        ('tp_bom_item'::regclass,'tp_bom_item_sku'::regclass,'sys_data_log'::regclass)
                        AND tgenabled<>'O'
                        """)).isZero();
                connection.commit();
                evidence.put("allIdentityAndPriceDiffsVerified", true);
            } finally {
                // SET LOCAL restores on both commit and rollback. Explicit restoration
                // also guards this pooled session if a future edit adds a session SET.
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                try {
                    if (fixture != null) cleanup(connection, fixture);
                } finally {
                    for (var entry : previousSettings.entrySet()) {
                        configure(connection, entry.getKey(), entry.getValue(), false);
                    }
                    connection.clearWarnings();
                }
            }
            evidence.put("passed", true);
        } finally {
            Path output = Path.of("target/test-artifacts/a4/audit-scale-" + skuCount + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }

    private Fixture fixture(Connection connection, int count) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long category = number(connection, "INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id", "A4 scale " + suffix);
        long sizes = number(connection, "INSERT INTO bas_size_group(name,base_size) VALUES (?,'S0001') RETURNING id", "A4 scale " + suffix);
        long materialCategory = number(connection, "INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", "A4 scale " + suffix);
        long material = number(connection, """
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
                VALUES (?,'A4 scale material',?,'FABRIC','METER','METER') RETURNING id
                """, "A4M" + suffix, materialCategory);
        long materialSku = number(connection, "INSERT INTO bas_material_sku(material_id,code) VALUES (?,?) RETURNING id",
                material, "A4S" + suffix);
        long style = number(connection, """
                INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id)
                VALUES (?,'A4 scale style','FS','A4_SCALE',?,2026,'SPRING','OWN',?) RETURNING id
                """, "A4T" + suffix, category, sizes);
        long pack = number(connection, "INSERT INTO tp_tech_pack(style_id,stage,version) VALUES (?,'PROD',1) RETURNING id", style);
        execute(connection, """
                INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_mode,usage_qty,unit,ref_price)
                SELECT ?,n,'MAIN',?,'BY_SIZE','UNIFORM',20,'METER',10 FROM generate_series(1,10) n
                """, pack, material);
        execute(connection, """
                INSERT INTO tp_bom_item_sku(bom_item_id,size_code,material_sku_id,ref_price)
                SELECT p.id,'S'||lpad(n::text,4,'0'),?,10
                FROM tp_bom_item p CROSS JOIN generate_series(1,?) n WHERE p.tech_pack_id=? ORDER BY p.seq,n
                """, materialSku, count / PARENTS, pack);
        return new Fixture(pack, material, materialSku, style, category, sizes, materialCategory,
                text(connection, "SELECT tenant_id FROM sys_deployment_namespace WHERE singleton"),
                parents(connection, pack), children(connection, pack));
    }

    private void cleanup(Connection connection, Fixture fixture) throws SQLException {
        // The seed is committed separately from replacement so event counts are
        // exact. Remove only this fixture in FK order; append-only audit stays.
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    DELETE FROM tp_bom_item_sku WHERE bom_item_id IN
                    (SELECT id FROM tp_bom_item WHERE tech_pack_id=?)
                    """, fixture.pack());
            execute(connection, "DELETE FROM tp_bom_item WHERE tech_pack_id=?", fixture.pack());
            execute(connection, "DELETE FROM tp_tech_pack WHERE id=?", fixture.pack());
            execute(connection, "DELETE FROM sty_style WHERE id=?", fixture.style());
            execute(connection, "DELETE FROM bas_material_sku WHERE id=?", fixture.materialSku());
            execute(connection, "DELETE FROM bas_material WHERE id=?", fixture.material());
            execute(connection, "DELETE FROM bas_category WHERE id=?", fixture.category());
            execute(connection, "DELETE FROM bas_size_group WHERE id=?", fixture.sizeGroup());
            execute(connection, "DELETE FROM bas_material_category WHERE id=?", fixture.materialCategory());
            connection.commit();
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private void captureFinalInsert(Connection connection, long parent, long sku, String size,
                                    List<JsonNode> plans) throws Exception {
        for (var entry : Map.of("auto_explain.log_nested_statements", "on", "auto_explain.log_analyze", "on",
                "auto_explain.log_timing", "off", "auto_explain.log_buffers", "on", "auto_explain.log_format", "json",
                "auto_explain.log_level", "notice", "client_min_messages", "notice").entrySet()) {
            configure(connection, entry.getKey(), entry.getValue(), true);
        }
        connection.clearWarnings();
        configure(connection, "auto_explain.log_min_duration", "0", true);
        try (PreparedStatement statement = prepare(connection, """
                INSERT INTO tp_bom_item_sku(bom_item_id,size_code,material_sku_id,ref_price) VALUES (?,?,?,12)
                """, parent, size, sku)) {
            statement.clearWarnings();
            assertThat(statement.executeUpdate()).isOne();
            Set<String> seen = new HashSet<>();
            collectNotices(statement.getWarnings(), plans, seen);
            collectNotices(connection.getWarnings(), plans, seen);
            statement.clearWarnings();
        } finally {
            configure(connection, "auto_explain.log_min_duration", "-1", true);
            connection.clearWarnings();
        }
    }

    private void collectNotices(SQLWarning warning, List<JsonNode> plans, Set<String> seen) throws Exception {
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            String message = current instanceof PSQLWarning postgres
                    ? postgres.getServerErrorMessage().getMessage() : current.getMessage();
            int beginning = message.indexOf("plan:\n");
            if (beginning >= 0 && seen.add(message)) plans.add(json.readTree(message.substring(beginning + 6)));
        }
    }

    private Map<String, JsonNode> replacementPlans(List<JsonNode> plans) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (JsonNode plan : plans) {
            String query = plan.path("Query Text").asText().stripLeading();
            if (query.startsWith("SELECT previous_row_id")) result.put("parentReplacement", plan);
            else if (query.startsWith("SELECT row_id") && query.contains("previous_parent")) result.put("deletedChild", plan);
            else if (query.startsWith("previous_id IS NOT NULL") && query.contains("NOT EXISTS")) result.put("previousIdentity", plan);
            else if (query.startsWith("SELECT jsonb_object_agg") && query.contains("sys_data_log")) result.put("deletedIdentity", plan);
        }
        assertThat(result.keySet()).containsExactlyInAnyOrder("parentReplacement", "deletedChild", "previousIdentity", "deletedIdentity");
        return result;
    }

    private Map<String, Object> checkAccess(JsonNode explain, String index, String identity, int returned,
                                           int maximumExamined, boolean generic) {
        var scans = new ArrayList<JsonNode>();
        collectAuditScans(explain.path("Plan"), scans);
        assertThat(scans).as(index + " actual audit relation access").hasSize(1);
        JsonNode scan = scans.getFirst();
        assertThat(scan.path("Node Type").asText()).isIn("Index Scan", "Index Only Scan");
        assertThat(scan.path("Index Name").asText()).isEqualTo(index);
        String condition = scan.path("Index Cond").asText();
        assertThat(condition).contains("tenant_key", "root_table", "root_id", "transaction_id", identity);
        if (generic) assertThat(condition).as("late trigger execution uses its actual generic SPI plan").contains("$");
        assertThat(scan.path("Actual Rows").asDouble()).isEqualTo(returned);
        double loops = scan.path("Actual Loops").asDouble();
        double examined = (scan.path("Actual Rows").asDouble() + scan.path("Rows Removed by Filter").asDouble()
                + scan.path("Rows Removed by Index Recheck").asDouble()) * loops;
        assertThat(loops).isEqualTo(1);
        assertThat(examined).as("must not scan the root/transaction history for every child")
                .isLessThanOrEqualTo(maximumExamined);
        assertThat(scan.has("Shared Hit Blocks")).as("actual BUFFERS evidence").isTrue();
        return Map.of("index", index, "rowsReturned", returned, "rowsExamined", examined,
                "maximumRowsExamined", maximumExamined, "genericPlan", generic,
                "sharedBlocks", scan.path("Shared Hit Blocks").asLong() + scan.path("Shared Read Blocks").asLong());
    }

    private void collectAuditScans(JsonNode node, List<JsonNode> scans) {
        if (node.path("Relation Name").asText().equals("sys_data_log")) scans.add(node);
        node.path("Plans").valueStream().forEach(child -> collectAuditScans(child, scans));
    }

    private void assertIdentitiesAndDiffs(Connection connection, Fixture fixture, Map<Integer, Long> currentParents,
                                         long transaction, int count) throws Exception {
        Map<Dimension, Long> currentChildren = children(connection, fixture.pack());
        assertThat(currentChildren).hasSize(count);
        Map<Long, Long> expectedParents = new HashMap<>();
        Map<Long, Long> expectedChildren = new HashMap<>();
        currentParents.forEach((seq, id) -> expectedParents.put(id, fixture.parents().get(seq)));
        currentChildren.forEach((dimension, id) -> expectedChildren.put(id, fixture.children().get(dimension)));
        var seen = new HashSet<String>();
        try (PreparedStatement statement = prepare(connection, """
                SELECT table_name,row_id,previous_row_id,root_table,root_id,transaction_id,diff::text
                FROM sys_data_log WHERE tenant_key=? AND root_table='tp_tech_pack' AND root_id=?
                  AND transaction_id=? AND action='REPLACE'
                """, fixture.tenant(), fixture.pack(), transaction); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String table = rows.getString(1);
                long current = rows.getLong(2), previous = rows.getLong(3);
                assertThat(seen.add(table + ":" + previous)).isTrue();
                assertThat(current).isNotEqualTo(previous);
                assertThat(rows.getString(4)).isEqualTo("tp_tech_pack");
                assertThat(rows.getLong(5)).isEqualTo(fixture.pack());
                assertThat(rows.getLong(6)).isEqualTo(transaction);
                assertThat(table).isIn("tp_bom_item", "tp_bom_item_sku");
                assertThat(previous).isEqualTo((table.equals("tp_bom_item") ? expectedParents : expectedChildren).get(current));
                JsonNode diff = json.readTree(rows.getString(7));
                assertThat(diff.path("id").path("old").longValue()).isEqualTo(previous);
                assertThat(diff.path("id").path("new").longValue()).isEqualTo(current);
                assertThat(diff.path("ref_price").path("old").decimalValue()).isEqualByComparingTo("10");
                assertThat(diff.path("ref_price").path("new").decimalValue()).isEqualByComparingTo("12");
                if (table.equals("tp_bom_item")) {
                    assertThat(diff.path("usage_qty").path("old").decimalValue()).isEqualByComparingTo("20");
                    assertThat(diff.path("usage_qty").path("new").decimalValue()).isEqualByComparingTo("22");
                } else {
                    long newParent = diff.path("bom_item_id").path("new").longValue();
                    assertThat(diff.path("bom_item_id").path("old").longValue()).isEqualTo(expectedParents.get(newParent));
                }
            }
        }
        assertThat(seen).hasSize(count + PARENTS);
    }

    private Map<String, Long> counts(Connection connection, Fixture fixture, long transaction) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (PreparedStatement statement = prepare(connection, """
                SELECT action,count(*) FROM sys_data_log WHERE tenant_key=? AND root_table='tp_tech_pack'
                AND root_id=? AND transaction_id=? GROUP BY action
                """, fixture.tenant(), fixture.pack(), transaction); var rows = statement.executeQuery()) {
            while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
        }
        return counts;
    }

    private Map<Integer, Long> parents(Connection connection, long pack) throws SQLException {
        var parents = new LinkedHashMap<Integer, Long>();
        try (PreparedStatement statement = prepare(connection, "SELECT seq,id FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack);
             var rows = statement.executeQuery()) {
            while (rows.next()) parents.put(rows.getInt(1), rows.getLong(2));
        }
        assertThat(parents).hasSize(PARENTS);
        return parents;
    }

    private Map<Dimension, Long> children(Connection connection, long pack) throws SQLException {
        var children = new LinkedHashMap<Dimension, Long>();
        try (PreparedStatement statement = prepare(connection, """
                SELECT p.seq,s.size_code,s.id FROM tp_bom_item p JOIN tp_bom_item_sku s ON s.bom_item_id=p.id
                WHERE p.tech_pack_id=? ORDER BY p.seq,s.size_code
                """, pack); var rows = statement.executeQuery()) {
            while (rows.next()) children.put(new Dimension(rows.getInt(1), rows.getString(2)), rows.getLong(3));
        }
        return children;
    }

    private Map<String, String> settings(Connection connection) throws SQLException {
        var settings = new LinkedHashMap<String, String>();
        for (String name : EXPLAIN_SETTINGS) settings.put(name, text(connection, "SELECT current_setting(?)", name));
        return settings;
    }

    private void configure(Connection connection, String name, String value, boolean local) throws SQLException {
        execute(connection, "SELECT set_config(?,?,?)", name, value, local);
    }

    private JsonNode explain(Connection connection, String sql, Object... parameters) throws Exception {
        return json.readTree(text(connection, "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, parameters)).get(0);
    }

    private static String size(int ordinal) { return "S%04d".formatted(ordinal); }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement;
        } catch (SQLException failure) { statement.close(); throw failure; }
    }

    private static int execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.execute();
            return statement.getUpdateCount();
        }
    }

    private static long number(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static String text(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private record Dimension(int parentSequence, String size) {}
    private record Fixture(long pack, long material, long materialSku, long style, long category, long sizeGroup,
                           long materialCategory, String tenant,
                           Map<Integer, Long> parents, Map<Dimension, Long> children) {}
}
