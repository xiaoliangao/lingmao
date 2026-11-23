package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P63：真实登录、HTTP、PostgreSQL 与审计触发器；历史空主体只在完整来源证明后修复。 */
class A1MrpCompanyHttpIT extends A2BusinessFixture {
    @Autowired DataSource dataSource;

    @Test void emptySearchPathKeepsAuditProjectionCommitAndRollbackExact() throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long calc = create(writer, f.company(), sourceOrder(f, writer, "APPROVED", f.company()));
        Map<String, Object> before = snapshot(calc);
        for (boolean commit : List.of(false, true)) {
            String request = java.util.UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                JdbcTemplate isolated = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                isolated.execute("SET LOCAL search_path = ''");
                assertThat(isolated.queryForObject("SELECT pg_catalog.cardinality(pg_catalog.current_schemas(false))", Integer.class)).isZero();
                isolated.queryForObject("SELECT pg_catalog.set_config('scm.audit_request',?,true)", String.class, request);
                JsonNode projection = json.readTree(isolated.queryForObject("SELECT public.scm_mrp_audit_projection(?::jsonb)::text", String.class,
                        "{\"id\":9007199254740993,\"company_id\":9007199254740997,\"version\":1,\"remark\":\"private-note\",\"input_snapshot\":{\"private\":true}}"));
                assertThat(projection).isEqualTo(json.readTree("{\"id\":\"9007199254740993\",\"company_id\":\"9007199254740997\",\"version\":1}"));
                // 限定验证新 MRP 审计函数；改日期/版本/备注不依赖调用连接的默认 schema。
                assertThat(isolated.update("UPDATE public.mrp_calc SET version=version+1,need_date=need_date+1,remark=? WHERE id=?",
                        "private-empty-path-note", calc)).isEqualTo(1);
                JsonNode diff = json.readTree(isolated.queryForObject("SELECT diff::text FROM public.sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND request_id=?",
                        String.class, calc, request));
                assertThat(diff.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("need_date", "version");
                assertThat(diff.path("version").path("old").intValue()).isZero();
                assertThat(diff.path("version").path("new").intValue()).isEqualTo(1);
                assertThat(java.time.LocalDate.parse(diff.path("need_date").path("new").asText()))
                        .isEqualTo(java.time.LocalDate.parse(diff.path("need_date").path("old").asText()).plusDays(1));
                if (commit) connection.commit(); else connection.rollback();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, request)).isEqualTo(commit ? 1 : 0);
            assertHead(calc, f.company(), commit ? 1 : 0, "DRAFT");
            if (!commit) assertThat(snapshot(calc)).isEqualTo(before);
        }
    }

    @Test void emptySearchPathCompanyBackfillKeepsTagRegistryAndAuditsAtomic() throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long calc = create(writer, f.company(), sourceOrder(f, writer, "APPROVED", f.company()));
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        String name = "p63-path-" + suffix();
        long tag = jdbc.queryForObject("INSERT INTO biz_tag(tenant_key,name,canonical) SELECT tenant_id,?,? FROM sys_deployment_namespace WHERE singleton RETURNING id", Long.class, name, name);
        long registry = jdbc.queryForObject("INSERT INTO biz_tag_object(tenant_key,object_type,object_id,company_id) SELECT tenant_id,'MRP_CALC',?,NULL FROM sys_deployment_namespace WHERE singleton RETURNING id", Long.class, calc);
        jdbc.update("INSERT INTO biz_tag_link(tenant_key,object_ref_id,tag_id,seq) SELECT tenant_id,?,?,1 FROM sys_deployment_namespace WHERE singleton", registry, tag);
        Map<String, Object> before = snapshot(calc);
        Map<String, Object> registryBefore = jdbc.queryForMap("SELECT * FROM biz_tag_object WHERE id=?", registry);
        List<Map<String, Object>> linksBefore = jdbc.queryForList("SELECT * FROM biz_tag_link WHERE object_ref_id=?", registry);
        for (boolean commit : List.of(false, true)) {
            String request = java.util.UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                JdbcTemplate isolated = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                isolated.execute("SET LOCAL search_path = ''");
                assertThat(isolated.queryForObject("SELECT pg_catalog.cardinality(pg_catalog.current_schemas(false))", Integer.class)).isZero();
                isolated.queryForObject("SELECT pg_catalog.set_config('scm.audit_request',?,true)", String.class, request);
                assertThat(isolated.update("UPDATE public.mrp_calc SET company_id=?,version=version+1 WHERE id=? AND company_id IS NULL AND version=0", f.company(), calc)).isEqualTo(1);
                assertThat(isolated.queryForObject("SELECT company_id FROM public.biz_tag_object WHERE id=?", Long.class, registry)).isEqualTo(f.company());
                assertThat(isolated.queryForObject("SELECT version FROM public.biz_tag_object WHERE id=?", Integer.class, registry)).isZero();
                var logs = isolated.queryForList("SELECT table_name,root_table,root_id,diff::text,transaction_id FROM public.sys_data_log WHERE request_id=? ORDER BY table_name", request);
                assertThat(logs).hasSize(2);
                assertThat(logs.stream().map(r -> r.get("table_name")).toList()).containsExactly("biz_tag_object", "mrp_calc");
                assertThat(logs.stream().map(r -> r.get("transaction_id")).distinct().count()).isEqualTo(1);
                for (var log : logs) {
                    assertThat(log.get("root_table")).isEqualTo("mrp_calc");
                    assertThat(log.get("root_id")).isEqualTo(calc);
                    JsonNode diff = json.readTree((String) log.get("diff"));
                    assertThat(diff.path("company_id").path("old").isNull()).isTrue();
                    assertThat(diff.path("company_id").path("new").isString()).isTrue();
                    assertThat(diff.path("company_id").path("new").asText()).isEqualTo(Long.toString(f.company()));
                    if (log.get("table_name").equals("mrp_calc")) {
                        assertThat(diff.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("company_id", "version");
                        assertThat(diff.path("version").path("old").intValue()).isZero();
                        assertThat(diff.path("version").path("new").intValue()).isEqualTo(1);
                    } else assertThat(diff.has("version")).isFalse();
                }
                if (commit) connection.commit(); else connection.rollback();
            }
            assertHead(calc, commit ? f.company() : null, commit ? 1 : 0, "DRAFT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, request)).isEqualTo(commit ? 2 : 0);
            assertThat(jdbc.queryForList("SELECT * FROM biz_tag_link WHERE object_ref_id=?", registry)).isEqualTo(linksBefore);
            if (!commit) {
                assertThat(snapshot(calc)).isEqualTo(before);
                assertThat(jdbc.queryForMap("SELECT * FROM biz_tag_object WHERE id=?", registry)).isEqualTo(registryBefore);
            }
        }
        Map<String, Object> committed = snapshot(calc);
        // 路径固定只修复名字解析，原来的真实父头主体一致性守卫仍然拒绝伪造归属。
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate isolated = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            isolated.execute("SET LOCAL search_path = ''");
            assertThatThrownBy(() -> isolated.update("UPDATE public.biz_tag_object SET company_id=? WHERE id=?", f.otherCompany(), registry))
                    .isInstanceOfSatisfying(DataAccessException.class, failure ->
                            assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState()).isEqualTo("23514"));
            connection.rollback();
        }
        assertThat(snapshot(calc)).isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE id=?", Long.class, registry)).isEqualTo(f.company());
        assertThat(jdbc.queryForObject("SELECT version FROM biz_tag_object WHERE id=?", Integer.class, registry)).isZero();
    }

    @Test void createUsesSourceCompanyAndRejectsMissingWrongOrForgedCompany() throws Exception {
        Fixture f = fixture();
        Account writer = writer(f.otherCompany(), "ALL");
        long order = sourceOrder(f, writer, "APPROVED", f.company());
        Map<String, Object> body = map("orderIds", List.of(order));
        assertThat(call(writer, "POST", "/api/mrp-calcs", body, null).path("code").intValue()).isEqualTo(400);
        assertThat(call(writer, "POST", "/api/mrp-calcs", body, f.otherCompany()).path("code").intValue()).isEqualTo(403);
        assertThat(call(writer, "POST", "/api/mrp-calcs", map("orderIds", List.of(order), "companyId", f.otherCompany()),
                f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE create_by=?", Integer.class, writer.id())).isZero();
        long calc = create(writer, f.company(), order);
        JsonNode detail = ok(writer, "GET", path(calc), null, f.company());
        assertThat(detail.path("companyId").isString()).isTrue();
        assertThat(detail.path("companyId").asText()).isEqualTo(Long.toString(f.company()));
        assertHead(calc, f.company(), 0, "DRAFT");

        JsonNode inserted = audit(calc, "INSERT");
        assertThat(inserted.path("company_id").path("new").isString()).isTrue();
        assertThat(inserted.path("company_id").path("new").asText()).isEqualTo(Long.toString(f.company()));
        assertThat(inserted.has("input_snapshot")).isFalse();
        assertThat(inserted.has("remark")).isFalse();
        JsonNode history = history(writer, calc, f.company());
        assertThat(history.valueStream().anyMatch(r -> r.path("action").asText().equals("INSERT")
                && r.path("diff").path("company_id").path("new").asText().equals(Long.toString(f.company())))).isTrue();
        evidence("p63-created-company.json", history);
    }

    @Test void draftRecalcRepairsLegacyNullWithOneVersionAndOneAuditedUpdate() throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long order = sourceOrder(f, writer, "APPROVED", f.company());
        long calc = create(writer, f.company(), order);
        jdbc.update("UPDATE mrp_calc SET company_id=NULL,remark='private-legacy-note' WHERE id=?", calc);
        assertThat(ok(writer, "GET", path(calc), null, f.company()).path("companyId").isNull()).isTrue();
        long watermark = lastAudit(calc);
        JsonNode saved = ok(writer, "POST", path(calc) + "/recalc", map("version", 0), f.company());
        assertThat(id(saved.path("id"))).isEqualTo(calc);
        assertThat(saved.path("version").intValue()).isEqualTo(1);
        assertHead(calc, f.company(), 1, "DRAFT");
        assertRepairAudit(calc, watermark, f.company(), 0, writer.id());
        Map<String, Object> state = snapshot(calc);
        assertThat(call(writer, "POST", path(calc) + "/recalc", map("version", 0), f.company()).path("code").intValue()).isEqualTo(1002);
        assertThat(snapshot(calc)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT diff::text FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND id>?",
                String.class, calc, watermark)).doesNotContain("private-legacy-note", "input_snapshot", "remark");
    }

    @Test void confirmedRecalcProvesAllSourcesBeforeSkippingClosedOrdersAndRepairsOnlyLegacyHead() throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long active = sourceOrder(f, writer, "APPROVED", f.company());
        long closed = sourceOrder(f, writer, "APPROVED", f.company());
        long calc = create(writer, f.company(), active, closed);
        ok(writer, "POST", path(calc) + "/confirm", map("version", 0), f.company());
        jdbc.update("UPDATE ord_order SET status='CLOSED' WHERE id=?", closed);
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        long watermark = lastAudit(calc);
        long next = id(ok(writer, "POST", path(calc) + "/recalc", map("version", 1), f.company()).path("id"));
        assertThat(next).isNotEqualTo(calc);
        assertHead(calc, f.company(), 2, "CONFIRMED");
        assertHead(next, f.company(), 0, "DRAFT");
        assertThat(jdbc.queryForList("SELECT order_id FROM mrp_calc_order WHERE calc_id=? ORDER BY order_id", Long.class, calc))
                .containsExactlyInAnyOrder(active, closed);
        assertThat(jdbc.queryForList("SELECT order_id FROM mrp_calc_order WHERE calc_id=?", Long.class, next)).containsExactly(active);
        assertRepairAudit(calc, watermark, f.company(), 1, writer.id());
        Long repairedTx = jdbc.queryForObject("SELECT transaction_id FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND id>?", Long.class, calc, watermark);
        assertThat(jdbc.queryForObject("SELECT transaction_id FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND action='INSERT'", Long.class, next))
                .isEqualTo(repairedTx);
        Map<String, Object> state = snapshot(calc);
        assertThat(call(writer, "POST", path(calc) + "/recalc", map("version", 1), f.company()).path("code").intValue()).isEqualTo(1002);
        assertThat(snapshot(calc)).isEqualTo(state);
        evidence("p63-confirmed-repair.json", history(writer, calc, f.company()));

        // 已有正确归属的确认头没有被修改；仅新草稿持久化主体。
        long regular = create(writer, f.company(), active);
        ok(writer, "POST", path(regular) + "/confirm", map("version", 0), f.company());
        long regularNext = id(ok(writer, "POST", path(regular) + "/recalc", map("version", 1), f.company()).path("id"));
        assertHead(regular, f.company(), 1, "CONFIRMED");
        assertHead(regularNext, f.company(), 0, "DRAFT");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lateFailureRollsBackCompanyVersionRowsSerialAndBothAudits(boolean confirmed) throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long order = sourceOrder(f, writer, "APPROVED", f.company());
        long calc = create(writer, f.company(), order);
        if (confirmed) ok(writer, "POST", path(calc) + "/confirm", map("version", 0), f.company());
        int version = confirmed ? 1 : 0;
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        Map<String, Object> before = snapshot(calc);
        String hook = "p63_fail_" + suffix();
        // 最后的旧单操作记录才抛错：公司、版本、新头、新行与字段审计已经执行，仍须全部回滚。
        jdbc.execute("CREATE FUNCTION " + hook + "() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN "
                + "IF NEW.doc_type='MRP_CALC' AND NEW.doc_id=" + calc + " AND NEW.action='recalc' THEN "
                + "RAISE EXCEPTION 'P63 injected late failure' USING ERRCODE='P2063'; END IF; RETURN NEW; END $f$");
        jdbc.execute("CREATE TRIGGER " + hook + " BEFORE INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + hook + "()");
        try {
            var failed = raw(writer, "POST", path(calc) + "/recalc", map("version", version), f.company(), java.util.UUID.randomUUID().toString());
            assertThat(node(failed).path("code").intValue()).isNotZero();
            String trace = failed.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, trace)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE request_id=?", Integer.class, trace)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + hook + " ON biz_doc_log");
            jdbc.execute("DROP FUNCTION " + hook + "()");
        }
        assertThat(snapshot(calc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE prev_calc_id=?", Integer.class, calc)).isZero();
        assertHead(calc, null, version, confirmed ? "CONFIRMED" : "DRAFT");
        long retried = id(ok(writer, "POST", path(calc) + "/recalc", map("version", version), f.company()).path("id"));
        assertHead(calc, f.company(), version + 1, confirmed ? "CONFIRMED" : "DRAFT");
        assertHead(retried, f.company(), confirmed ? 0 : 1, "DRAFT");
    }

    @Test void unprovableAndConflictingLegacyOwnershipNeverFallsBackToHeaderOrCallerDefault() throws Exception {
        Fixture f = fixture(); Account writer = writer(f.company(), "ALL");
        long noSource = jdbc.queryForObject("INSERT INTO mrp_calc(no,status,create_by) VALUES(?,'DRAFT',?) RETURNING id",
                Long.class, "P63-legacy-" + suffix(), writer.id());
        // 旧无来源记录依然能读；重算不能凭当前页面主体把历史归属造出来。
        assertThat(ok(writer, "GET", path(noSource), null, f.company()).path("companyId").isNull()).isTrue();
        Map<String, Object> before = snapshot(noSource);
        assertThat(call(writer, "POST", path(noSource) + "/recalc", map("version", 0), f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(snapshot(noSource)).isEqualTo(before);

        long active = sourceOrder(f, writer, "APPROVED", f.company());
        long skipped = sourceOrder(f, writer, "APPROVED", f.company());
        long calc = create(writer, f.company(), active, skipped);
        ok(writer, "POST", path(calc) + "/confirm", map("version", 0), f.company());
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        jdbc.update("UPDATE ord_order SET status='CLOSED',company_id=? WHERE id=?", f.otherCompany(), skipped);
        before = snapshot(calc);
        assertThat(call(writer, "POST", path(calc) + "/recalc", map("version", 1), f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(snapshot(calc)).isEqualTo(before);
        assertHead(calc, null, 1, "CONFIRMED");

        long conflict = create(writer, f.company(), active);
        jdbc.update("UPDATE mrp_calc SET company_id=? WHERE id=?", f.otherCompany(), conflict);
        before = snapshot(conflict);
        assertThat(call(writer, "POST", path(conflict) + "/recalc", map("version", 0), f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(snapshot(conflict)).isEqualTo(before);
        assertHead(conflict, f.otherCompany(), 0, "DRAFT");
    }

    @Test void recalcRetainsHeaderAndSourceDataScopeGuards() throws Exception {
        Fixture f = fixture(); Account owner = writer(f.company(), "SELF"), outsider = writer(f.company(), "SELF");
        long order = sourceOrder(f, owner, "APPROVED", f.company());
        long calc = create(owner, f.company(), order);
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        Map<String, Object> before = snapshot(calc);
        assertThat(call(outsider, "POST", path(calc) + "/recalc", map("version", 0), f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(snapshot(calc)).isEqualTo(before);
        // 只有头可见仍不足以访问别人的真实来源订单。
        jdbc.update("UPDATE mrp_calc SET create_by=? WHERE id=?", outsider.id(), calc);
        before = snapshot(calc);
        assertThat(call(outsider, "POST", path(calc) + "/recalc", map("version", 0), f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(snapshot(calc)).isEqualTo(before);
    }

    private Account writer(long company, String scope) throws Exception {
        return custom(company, scope, "purchase:mrp:add", "purchase:mrp:list", "purchase:mrp:edit", "system:audit:query", "techpack:cost:query");
    }
    private long create(Account actor, long company, Long... orders) throws Exception {
        return id(ok(actor, "POST", "/api/mrp-calcs", map("orderIds", List.of(orders)), company).path("id"));
    }
    private static String path(long calc) { return "/api/mrp-calcs/" + calc; }
    private void assertHead(long calc, Long company, int version, String status) {
        Map<String, Object> head = jdbc.queryForMap("SELECT company_id,version,status FROM mrp_calc WHERE id=?", calc);
        assertThat(head.get("company_id")).isEqualTo(company);
        assertThat(head.get("version")).isEqualTo(version);
        assertThat(head.get("status")).isEqualTo(status);
    }
    private long lastAudit(long calc) {
        return jdbc.queryForObject("SELECT coalesce(max(id),0) FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=?", Long.class, calc);
    }
    private JsonNode audit(long calc, String action) {
        return json.readTree(jdbc.queryForObject("SELECT diff::text FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND action=? ORDER BY id DESC LIMIT 1", String.class, calc, action));
    }
    private JsonNode history(Account actor, long calc, long company) throws Exception {
        return ok(actor, "GET", "/api/data-logs?rootTable=mrp_calc&rootId=" + calc + "&limit=100", null, company);
    }
    private void assertRepairAudit(long calc, long after, long company, int version, long actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT diff::text,actor_user_id,request_id,operation FROM sys_data_log WHERE table_name='mrp_calc' AND row_id=? AND id>?", calc, after);
        assertThat(rows).hasSize(1);
        JsonNode diff = json.readTree((String) rows.getFirst().get("diff"));
        assertThat(diff.path("company_id").path("old").isNull()).isTrue();
        assertThat(diff.path("company_id").path("new").asText()).isEqualTo(Long.toString(company));
        assertThat(diff.path("version").path("old").intValue()).isEqualTo(version);
        assertThat(diff.path("version").path("new").intValue()).isEqualTo(version + 1);
        assertThat(rows.getFirst().get("actor_user_id")).isEqualTo(actor);
        assertThat(rows.getFirst().get("request_id")).isNotNull();
        assertThat(rows.getFirst().get("operation").toString()).contains("recalc");
    }
    private Map<String, Object> snapshot(long calc) {
        Map<String, Object> result = new LinkedHashMap<>();
        String roots = "SELECT id FROM mrp_calc WHERE id=" + calc + " OR prev_calc_id=" + calc;
        result.put("heads", jdbc.queryForList("SELECT * FROM mrp_calc WHERE id IN (" + roots + ") ORDER BY id"));
        result.put("orders", jdbc.queryForList("SELECT * FROM mrp_calc_order WHERE calc_id IN (" + roots + ") ORDER BY calc_id,order_id"));
        result.put("items", jdbc.queryForList("SELECT * FROM mrp_calc_item WHERE calc_id IN (" + roots + ") ORDER BY id"));
        result.put("transits", jdbc.queryForList("SELECT * FROM mrp_calc_transit WHERE calc_item_id IN (SELECT id FROM mrp_calc_item WHERE calc_id IN (" + roots + ")) ORDER BY calc_item_id,purchase_item_id"));
        result.put("docLogs", jdbc.queryForList("SELECT * FROM biz_doc_log WHERE doc_type='MRP_CALC' AND doc_id IN (" + roots + ") ORDER BY id"));
        result.put("fieldLogs", jdbc.queryForList("SELECT * FROM sys_data_log WHERE root_table='mrp_calc' AND root_id IN (" + roots + ") ORDER BY id"));
        result.put("serial", jdbc.queryForList("SELECT * FROM sys_serial WHERE seq_key LIKE 'SL%' ORDER BY seq_key"));
        return result;
    }
    private void evidence(String name, JsonNode value) throws Exception {
        Path dir = Path.of("target", "a1-mrp-company"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), json.writeValueAsString(value));
    }
}
