package com.lingmao.scm.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 HTTP / PostgreSQL 字段事实；FG 副作用同步完成，不伪造 Outbox 消息。 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"lingmao.observability.slow-sql-ms=20", "lingmao.proxy.trusted-cidrs="})
class S4FgAuditHttpIT extends A2BusinessFixture {
    @Autowired PlatformTransactionManager transactions;
    @Autowired Flyway flyway;
    private final List<Map<String, Object>> exchanges = new ArrayList<>();

    @AfterEach void archiveActualHttp(TestInfo info) throws Exception {
        evidence(info.getTestMethod().orElseThrow().getName() + ".http.json", exchanges);
    }

    @Override HttpResponse<byte[]> raw(Account actor, String method, String path, Object body, Long company, String key) throws Exception {
        var response = super.raw(actor, method, path, body, company, key);
        recordReply(method, path, company, response);
        return response;
    }

    private static final Set<String> DOC_FIELDS = Set.of("id", "no", "company_id", "kind", "doc_type", "status",
            "from_warehouse_id", "to_warehouse_id", "prd_receipt_id", "order_id", "doc_date", "audit_by", "audit_time", "version");
    private static final Set<String> ITEM_FIELDS = Set.of("id", "doc_id", "seq", "style_sku_id", "qty",
            "prd_receipt_item_id", "order_item_id", "price", "amount");
    private static final Set<String> STOCK_FIELDS = Set.of("id", "company_id", "warehouse_id", "style_sku_id",
            "location_id", "qty", "movement_revision");

    @Test void realItemUpdatesKeepIdentityAndOrderOnlyPermissionProtectsBothSidesOfTheDiff() throws Exception {
        Fixture f = fixture();
        Account maker = actor(f.company(), "SELF"), reviewer = actor(f.company(), "ALL");
        Account costOnly = custom(f.company(), "ALL", "system:audit:query", "fg:doc:list", "techpack:cost:query");
        Account orderOnly = custom(f.company(), "ALL", "system:audit:query", "fg:doc:list", "fg:stock:list", "order:price:query");
        long warehouse = productWarehouse();
        long opening = manual(maker, f, warehouse, 10);
        success(action(reviewer, opening, f.company(), "audit", 0, trace("opening")));
        long order = sourceOrder(f, maker, "APPROVED", f.company());
        jdbc.update("UPDATE ord_order_item SET price=.125,amount=1.25 WHERE order_id=?", order);
        String createTrace = trace("create");
        var create = traced(maker, "POST", "/api/fg-docs", map("companyId", f.company(), "docType", "SHIP_OUT",
                "orderId", order, "warehouseId", warehouse, "docDate", today(), "shipTo", "S4 private delivery address",
                "logisticsNo", "S4-private-logistics", "remark", "S4 private document note",
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 1, "remark", "S4 private item note"))), f.company(), createTrace);
        long doc = id(success(create).path("id"));
        long item = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc);
        String saveTrace = trace("save");
        var saved = traced(maker, "PUT", "/api/fg-docs/" + doc, map("companyId", f.company(), "warehouseId", warehouse,
                "docDate", today(), "version", 0, "shipTo", "S4 private replacement address", "logisticsNo", "S4-private-next-logistics",
                "remark", "S4 private replacement note", "items", List.of(map("styleSkuId", f.styleSku(), "qty", 2))), f.company(), saveTrace);
        assertThat(success(saved).intValue()).isOne();
        assertThat(jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc)).isEqualTo(item);
        JsonNode savedItem = byTrace(history(orderOnly, "fg_doc", doc, true, f.company()), saveTrace, "fg_doc_item");
        assertThat(savedItem.path("action").stringValue()).isEqualTo("UPDATE");
        assertThat(savedItem.path("rowId").stringValue()).isEqualTo(Long.toString(item));
        assertChange(savedItem, "qty", "1", "2");
        assertThat(savedItem.path("diff").path("amount").path("old").decimalValue()).isEqualByComparingTo("0.13");
        assertThat(savedItem.path("diff").path("amount").path("new").decimalValue()).isEqualByComparingTo("0.25");

        // 实际订单行在保存后变化；审核仍在 DRAFT 时重算，形成同一明细的真实 UPDATE。
        jdbc.update("UPDATE ord_order_item SET price=.135,amount=1.35 WHERE order_id=?", order);
        String auditTrace = trace("audit");
        var posted = action(reviewer, doc, f.company(), "audit", 1, auditTrace);
        success(posted);
        JsonNode priced = history(orderOnly, "fg_doc", doc, true, f.company());
        JsonNode repriced = byTrace(priced, auditTrace, "fg_doc_item");
        assertThat(repriced.path("action").stringValue()).isEqualTo("UPDATE");
        assertThat(repriced.path("rowId").stringValue()).isEqualTo(Long.toString(item));
        assertThat(repriced.path("diff").path("price").path("old").decimalValue()).isEqualByComparingTo("0.125");
        assertThat(repriced.path("diff").path("price").path("new").decimalValue()).isEqualByComparingTo("0.135");
        assertThat(repriced.path("diff").path("amount").path("old").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(repriced.path("diff").path("amount").path("new").decimalValue()).isEqualByComparingTo("0.27");
        assertThat(priced.valueStream().map(row -> row.path("action").stringValue()).toList()).doesNotContain("REPLACE");
        JsonNode hidden = history(costOnly, "fg_doc", doc, true, f.company());
        for (JsonNode row : hidden) {
            assertThat(row.path("diff").properties().stream().map(Map.Entry::getKey).toList()).doesNotContain("price", "amount");
        }
        assertThat(hidden.toString()).doesNotContain("\"price\"", "\"amount\"", "0.125", "0.135", "0.27");
        assertThat(jdbc.queryForList("SELECT DISTINCT price_domain FROM sys_data_log WHERE root_table='fg_doc' AND root_id=?", String.class, doc))
                .containsExactly("ORDER");
        assertPersistedFields("fg_doc", doc, DOC_FIELDS);
        assertPersistedFields("fg_doc_item", item, ITEM_FIELDS);
        String rawDiffs = jdbc.queryForObject("SELECT jsonb_agg(diff)::text FROM sys_data_log WHERE root_table='fg_doc' AND root_id=?", String.class, doc);
        assertThat(rawDiffs).doesNotContain("private", "logistics", "ship_to", "remark", "reason", "create_by", "update_by");
        assertStringIds(priced);

        long stock = jdbc.queryForObject("SELECT id FROM fg_stock WHERE company_id=? AND warehouse_id=? AND style_sku_id=?", Long.class,
                f.company(), warehouse, f.styleSku());
        JsonNode stockHistory = history(orderOnly, "fg_stock", stock, false, f.company());
        JsonNode stockChange = byTrace(stockHistory, auditTrace, "fg_stock");
        assertChange(stockChange, "qty", "10", "8");
        assertPersistedFields("fg_stock", stock, STOCK_FIELDS);
        assertTrace(posted, auditTrace, doc, reviewer.id(), 3);
        String reverseTrace = trace("reverse");
        var reversed = action(reviewer, doc, f.company(), "unaudit", 2, reverseTrace);
        success(reversed);
        assertTrace(reversed, reverseTrace, doc, reviewer.id(), 2);
        JsonNode reverseDoc = byTrace(history(orderOnly, "fg_doc", doc, true, f.company()), reverseTrace, "fg_doc");
        assertChange(reverseDoc, "status", "APPROVED", "DRAFT");
        var ledger = jdbc.queryForList("SELECT id::text,change_qty,balance_qty,reversal_of_log_id::text,request_id FROM fg_stock_log WHERE fg_doc_id=? ORDER BY id", doc);
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).get("reversal_of_log_id")).isEqualTo(ledger.getFirst().get("id"));
        evidence("price-and-update-diff.json", map("orderOnly", priced, "costOnly", hidden, "stockChange", stockChange,
                "ledger", ledger, "reverseDocument", reverseDoc, "putItemId", Long.toString(item),
                "storedDocumentAudit", storedAudit("fg_doc", doc, true), "storedStockAudit", storedAudit("fg_stock", stock, false)));
    }

    @Test void auditTargetsRequireTheirRealFgPermissionAndCompanyAndDoNotExpandSelfScope() throws Exception {
        Fixture f = fixture();
        Account maker = actor(f.company(), "SELF"), reviewer = actor(f.company(), "ALL");
        Account auditOnly = custom(f.company(), "ALL", "system:audit:query");
        Account docReader = custom(f.company(), "ALL", "system:audit:query", "fg:doc:list");
        Account stockReader = custom(f.company(), "ALL", "system:audit:query", "fg:stock:list");
        Account selfReader = custom(f.company(), "SELF", "system:audit:query", "fg:doc:list", "fg:stock:list");
        Account noAudit = actor(f.company(), "ALL");
        long warehouse = productWarehouse(), doc = manual(maker, f, warehouse, 1);
        success(action(reviewer, doc, f.company(), "audit", 0, trace("scope-post")));
        long item = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc);
        long stock = jdbc.queryForObject("SELECT id FROM fg_stock WHERE company_id=? AND warehouse_id=? AND style_sku_id=?", Long.class,
                f.company(), warehouse, f.styleSku());
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var target : List.of(map("table", "fg_doc", "id", doc), map("table", "fg_doc_item", "id", item), map("table", "fg_stock", "id", stock))) {
            String path = "/api/data-logs?table=" + target.get("table") + "&rowId=" + target.get("id");
            Account allowed = target.get("table").equals("fg_stock") ? stockReader : docReader;
            JsonNode missingPermission = call(auditOnly, "GET", path, null, f.company());
            JsonNode crossCompany = call(allowed, "GET", path, null, f.otherCompany());
            JsonNode missingCompany = call(allowed, "GET", path, null, null);
            JsonNode self = call(selfReader, "GET", path, null, f.company());
            JsonNode noGlobalAudit = call(noAudit, "GET", path, null, f.company());
            assertThat(missingPermission.path("code").intValue()).isEqualTo(404);
            assertThat(crossCompany.path("code").intValue()).isEqualTo(404);
            assertThat(missingCompany.path("code").intValue()).isEqualTo(400);
            assertThat(self.path("code").intValue()).isEqualTo(403);
            assertThat(noGlobalAudit.path("code").intValue()).isEqualTo(403);
            assertThat(ok(allowed, "GET", path, null, f.company()).isArray()).isTrue();
            evidence.add(map("table", target.get("table"), "missingFgPermission", missingPermission, "crossCompany", crossCompany,
                    "missingCompany", missingCompany, "self", self, "noGlobalAudit", noGlobalAudit));
        }
        // 单据查询权不能代替库存查询权；库存查询权也不能代替单据查询权。
        assertThat(call(docReader, "GET", "/api/data-logs?table=fg_stock&rowId=" + stock, null, f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(call(stockReader, "GET", "/api/data-logs?table=fg_doc_item&rowId=" + item, null, f.company()).path("code").intValue()).isEqualTo(404);
        evidence("target-permissions.json", evidence);
    }

    @Test void removedItemsAndDeletedDraftsUseOnlyTheirFinalRealDeleteAsCompanyProof() throws Exception {
        Fixture f = fixture(); Account maker = actor(f.company(), "SELF");
        Account reader = custom(f.company(), "ALL", "system:audit:query", "fg:doc:list");
        Account noFg = custom(f.company(), "ALL", "system:audit:query");
        long warehouse = productWarehouse();
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,'L')", f.sizeGroup());
        long secondSku = jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,'L') RETURNING id",
                Long.class, f.style(), "S4FGL" + suffix(), f.color());
        long doc = id(success(traced(maker, "POST", "/api/fg-docs", manualBody(f, warehouse,
                List.of(map("styleSkuId", f.styleSku(), "qty", 1), map("styleSkuId", secondSku, "qty", 1))), f.company(), trace("delete-create"))).path("id"));
        long removedItem = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=? AND style_sku_id=?", Long.class, doc, secondSku);
        String removeTrace = trace("item-remove");
        success(traced(maker, "PUT", "/api/fg-docs/" + doc, map("companyId", f.company(), "warehouseId", warehouse,
                "docDate", today(), "reason", "OTHER", "version", 0, "items", List.of(map("styleSkuId", f.styleSku(), "qty", 2))), f.company(), removeTrace));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc_item WHERE id=?", Integer.class, removedItem)).isZero();
        JsonNode itemWhileParentLive = history(reader, "fg_doc_item", removedItem, false, f.company());
        JsonNode deletedItem = byTrace(itemWhileParentLive, removeTrace, "fg_doc_item");
        assertThat(deletedItem.path("action").stringValue()).isEqualTo("DELETE");
        assertThat(deletedItem.path("diff").path("doc_id").path("old").stringValue()).isEqualTo(Long.toString(doc));
        String deleteTrace = trace("draft-delete");
        success(traced(maker, "DELETE", "/api/fg-docs/" + doc + "?companyId=" + f.company() + "&version=1", null, f.company(), deleteTrace));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc WHERE id=?", Integer.class, doc)).isZero();
        JsonNode deletedRoot = history(reader, "fg_doc", doc, true, f.company());
        JsonNode headerDelete = byTrace(deletedRoot, deleteTrace, "fg_doc");
        assertThat(headerDelete.path("action").stringValue()).isEqualTo("DELETE");
        assertThat(headerDelete.path("diff").path("company_id").path("old").stringValue()).isEqualTo(Long.toString(f.company()));
        assertThat(history(reader, "fg_doc_item", removedItem, false, f.company())).isEqualTo(itemWhileParentLive);
        for (String path : List.of("/api/data-logs?rootTable=fg_doc&rootId=" + doc,
                "/api/data-logs?table=fg_doc_item&rowId=" + removedItem)) {
            assertThat(call(reader, "GET", path, null, f.otherCompany()).path("code").intValue()).isEqualTo(404);
            assertThat(call(noFg, "GET", path, null, f.company()).path("code").intValue()).isEqualTo(404);
        }
        assertThat(call(reader, "GET", "/api/data-logs?table=fg_doc&rowId=9223372036854775807", null, f.company()).path("code").intValue()).isEqualTo(404);
        // DELETE 审计来自真实 HTTP 删除；保留的 DocLog 同样可凭 request_id 追到删除请求。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=? AND request_id=?", Integer.class, doc, deleteTrace)).isOne();
        evidence("deleted-history.json", map("removedItem", itemWhileParentLive, "deletedRoot", deletedRoot,
                "storedDocumentAudit", storedAudit("fg_doc", doc, true),
                "proofSource", "final same-namespace DELETE event; no historical scan fallback"));
    }

    @Test void failureAfterPostingRollsBackAllThreeFactsAndLeavesSafeTraceableFailure(CapturedOutput output) throws Exception {
        Fixture f = fixture(); Account maker = actor(f.company(), "SELF"), reviewer = actor(f.company(), "ALL");
        long warehouse = productWarehouse(), doc = manual(maker, f, warehouse, 3);
        int auditBefore = jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table='fg_doc' AND root_id=?", Integer.class, doc);
        int docLogsBefore = jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=?", Integer.class, doc);
        String marker = "zz_s4_fg_failure_" + suffix();
        jdbc.execute("CREATE FUNCTION " + marker + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.doc_type='FG_DOC' AND NEW.doc_id=" + doc
                + " AND NEW.action='audit' THEN PERFORM pg_sleep(.05); RAISE EXCEPTION 'password=FG-Synthetic-Failure-9374 bankAccount=6222023333333333333 SELECT fg_private_probe'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " AFTER INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + marker + "()");
        String trace = trace("rollback");
        try {
            var response = action(reviewer, doc, f.company(), "audit", 0, trace);
            JsonNode error = node(response);
            assertThat(error.path("code").intValue()).isEqualTo(500);
            assertThat(error.path("msg").stringValue()).contains(trace);
            assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
            assertThat(error.toString()).doesNotContain("FG-Synthetic-Failure-9374", "6222023333333333333", "fg_private_probe",
                    "biz_doc_log", "PSQLException", "SQLException", "stackTrace", "INSERT INTO");
            assertThat(jdbc.queryForObject("SELECT status FROM fg_doc WHERE id=?", String.class, doc)).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT version FROM fg_doc WHERE id=?", Integer.class, doc)).isZero();
            int remainingBalanceRows = jdbc.queryForObject("SELECT count(*) FROM fg_stock WHERE company_id=? AND warehouse_id=? AND style_sku_id=?", Integer.class,
                    f.company(), warehouse, f.styleSku());
            int remainingLedgerRows = jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?", Integer.class, doc);
            int remainingFieldAuditRows = jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, trace);
            assertThat(remainingBalanceRows).isZero();
            assertThat(remainingLedgerRows).isZero();
            assertThat(remainingFieldAuditRows).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table='fg_doc' AND root_id=?", Integer.class, doc)).isEqualTo(auditBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=?", Integer.class, doc)).isEqualTo(docLogsBefore);
            Map<String, Object> failedOperation = jdbc.queryForMap("SELECT id::text,module,action,success,trace_id,error_msg FROM sys_oper_log WHERE trace_id=?", trace);
            assertThat(failedOperation.get("success")).isEqualTo(false);
            assertThat(json.writeValueAsString(failedOperation)).doesNotContain("FG-Synthetic-Failure-9374", "6222023333333333333", "SELECT fg_private_probe");
            assertThat(output.getAll()).doesNotContain("FG-Synthetic-Failure-9374", "6222023333333333333", "SELECT fg_private_probe");
            List<String> slow = output.getAll().lines().filter(line -> line.contains(trace) && line.contains("slow_sql")).toList();
            assertThat(slow).isNotEmpty();
            evidence("failed-post-rollback.json", map("response", error, "fieldAuditBefore", auditBefore, "docLogBefore", docLogsBefore,
                    "remainingLedgerRows", remainingLedgerRows, "remainingBalanceRows", remainingBalanceRows,
                    "remainingFieldAuditRows", remainingFieldAuditRows, "storedFailureOperationLog", failedOperation, "safeSlowSqlLines", slow,
                    "storedDocumentAuditAfterRollback", storedAudit("fg_doc", doc, true)));
        } finally {
            jdbc.execute("DROP TRIGGER " + marker + " ON biz_doc_log");
            jdbc.execute("DROP FUNCTION " + marker + "()");
        }
    }

    @Test void repeatableInstallsIdempotentlyAndRealLongIdentitiesAndAppendOnlyLogsRemainIntact() throws Exception {
        // 本次库由真实 Flyway 启动迁移；重跑 migrate 不篡改 checksum/history。
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE script='R__fg_field_audit.sql' AND success", Integer.class)).isOne();
        try (var stream = getClass().getResourceAsStream("/db/migration/R__fg_field_audit.sql")) {
            assertThat(stream).isNotNull();
            String ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (int repeat = 0; repeat < 2; repeat++) new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.execute(ddl));
        }
        assertThat(jdbc.queryForList("SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE t.tgname='fg_field_audit' AND NOT t.tgisinternal ORDER BY c.relname", String.class))
                .containsExactly("fg_doc", "fg_doc_item", "fg_stock");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='fg_stock_log'::regclass AND tgname='fg_field_audit'", Integer.class)).isZero();
        Fixture f = fixture(); Account reader = custom(f.company(), "ALL", "system:audit:query", "fg:doc:list", "fg:stock:list");
        long warehouse = productWarehouse();
        long doc = 9_007_199_254_740_993L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000L) * 4;
        long item = doc + 1, stock = doc + 2;
        jdbc.update("""
                INSERT INTO fg_doc(id,no,company_id,kind,doc_type,to_warehouse_id,doc_date,reason,create_by)
                OVERRIDING SYSTEM VALUE VALUES(?,?,?,'IN','OTHER_IN',?,CURRENT_DATE,'OTHER',?)
                """, doc, "S4BIG" + suffix(), f.company(), warehouse, reader.id());
        jdbc.update("INSERT INTO fg_doc_item(id,doc_id,seq,style_sku_id,qty) OVERRIDING SYSTEM VALUE VALUES(?,?,1,?,1)", item, doc, f.styleSku());
        jdbc.update("INSERT INTO fg_stock(id,company_id,warehouse_id,style_sku_id,qty) OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,0)", stock, f.company(), warehouse, f.styleSku());
        JsonNode root = history(reader, "fg_doc", doc, true, f.company());
        JsonNode balance = history(reader, "fg_stock", stock, false, f.company());
        assertStringIds(root); assertStringIds(balance);
        assertThat(root.valueStream().map(row -> row.path("rowId").stringValue()).toList())
                .containsExactlyInAnyOrder(Long.toString(doc), Long.toString(item));
        assertThat(root.valueStream().map(row -> row.path("rootId").stringValue()).toList()).containsOnly(Long.toString(doc));
        assertThat(root.valueStream().filter(row -> row.path("table").stringValue().equals("fg_doc_item")).findFirst().orElseThrow()
                .path("diff").path("doc_id").path("new").stringValue()).isEqualTo(Long.toString(doc));
        assertThat(balance.get(0).path("rowId").stringValue()).isEqualTo(Long.toString(stock));
        List<String> storedIdTypes = jdbc.queryForList("""
                SELECT jsonb_typeof(diff->'id'->'new') FROM sys_data_log
                WHERE (root_table='fg_doc' AND root_id=?) OR (table_name='fg_stock' AND row_id=?)
                """, String.class, doc, stock);
        assertThat(storedIdTypes).hasSize(3).containsOnly("string");
        // 余额没有删除 API；对零余额做真实 SQL 删除，仅验历史授权证明，不把它伪称业务入口。
        assertThat(jdbc.update("DELETE FROM fg_stock WHERE id=?", stock)).isOne();
        JsonNode deletedBalance = history(reader, "fg_stock", stock, false, f.company());
        assertThat(deletedBalance.get(0).path("action").stringValue()).isEqualTo("DELETE");
        assertThat(deletedBalance.get(0).path("diff").path("company_id").path("old").stringValue()).isEqualTo(Long.toString(f.company()));
        assertThat(call(reader, "GET", "/api/data-logs?table=fg_stock&rowId=" + stock, null, f.otherCompany()).path("code").intValue()).isEqualTo(404);

        Account maker = actor(f.company(), "SELF"), reviewer = actor(f.company(), "ALL");
        long postedDoc = manual(maker, f, productWarehouse(), 1);
        success(action(reviewer, postedDoc, f.company(), "audit", 0, trace("append-only")));
        long ledgerId = jdbc.queryForObject("SELECT id FROM fg_stock_log WHERE fg_doc_id=?", Long.class, postedDoc);
        long auditId = jdbc.queryForObject("SELECT id FROM sys_data_log WHERE table_name='fg_doc' AND row_id=? ORDER BY id DESC LIMIT 1", Long.class, postedDoc);
        long docLogId = jdbc.queryForObject("SELECT id FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=? ORDER BY id DESC LIMIT 1", Long.class, postedDoc);
        for (String sql : List.of("UPDATE fg_stock_log SET change_qty=change_qty+1 WHERE id=" + ledgerId,
                "DELETE FROM fg_stock_log WHERE id=" + ledgerId, "TRUNCATE fg_stock_log",
                "UPDATE sys_data_log SET diff='{}' WHERE id=" + auditId, "DELETE FROM sys_data_log WHERE id=" + auditId,
                "TRUNCATE sys_data_log", "UPDATE biz_doc_log SET action='altered' WHERE id=" + docLogId, "TRUNCATE biz_doc_log")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.update("DELETE FROM biz_doc_log WHERE id=?", docLogId)).isZero();
        assertThat(jdbc.queryForObject("SELECT change_qty FROM fg_stock_log WHERE id=?", Integer.class, ledgerId)).isOne();
        assertThat(jdbc.queryForObject("SELECT action FROM biz_doc_log WHERE id=?", String.class, docLogId)).isEqualTo("audit");
        assertThat(jdbc.queryForObject("SELECT action FROM sys_data_log WHERE id=?", String.class, auditId)).isEqualTo("UPDATE");
        evidence("migration-identity-append-only.json", map("docHistory", root, "stockHistory", balance, "deletedZeroBalance", deletedBalance, "storedIdTypes", storedIdTypes,
                "ddlReplays", 2, "flywayPendingMigrations", 0, "ledgerId", Long.toString(ledgerId), "appendOnlyAttemptsRejected", 8));
    }

    private Account actor(long company, String scope) throws Exception {
        return custom(company, scope, "fg:doc:list", "fg:doc:add", "fg:doc:edit", "fg:doc:remove", "fg:doc:audit", "fg:stock:list");
    }
    private long productWarehouse() {
        return jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'PRODUCT') RETURNING id", Long.class,
                "S4FG" + suffix(), "S4 FG audit warehouse");
    }
    private Object manualBody(Fixture f, long warehouse, List<Map<String, Object>> items) {
        return map("companyId", f.company(), "docType", "OTHER_IN", "warehouseId", warehouse, "docDate", today(), "reason", "OTHER", "items", items);
    }
    private long manual(Account maker, Fixture f, long warehouse, int qty) throws Exception {
        return id(success(traced(maker, "POST", "/api/fg-docs", manualBody(f, warehouse,
                List.of(map("styleSkuId", f.styleSku(), "qty", qty))), f.company(), trace("manual"))).path("id"));
    }
    private static String trace(String action) { return "s4-fg-" + action + "-" + UUID.randomUUID(); }
    private HttpResponse<byte[]> action(Account actor, long doc, long company, String action, int version, String trace) throws Exception {
        return traced(actor, "POST", "/api/fg-docs/" + doc + "/" + action + "?companyId=" + company, Map.of("version", version), company, trace);
    }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, Long company, String trace) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json").header("X-Request-Id", trace);
        if (company != null) request.header("X-Company-Id", company.toString());
        if (!method.equals("GET")) request.header("Idempotency-Key", UUID.randomUUID().toString());
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        recordReply(method, path, company, response);
        return response;
    }
    private void recordReply(String method, String path, Long company, HttpResponse<byte[]> response) {
        // 认证响应包含会话凭据，绝不归档；业务响应为本测试匿名夹具的原始 JSON。
        if (path.startsWith("/api/auth/")) return;
        exchanges.add(map("method", method, "path", path, "companyHeader", company == null ? null : company.toString(),
                "httpStatus", response.statusCode(), "requestId", response.headers().firstValue("X-Request-Id").orElse(null),
                "response", node(response)));
    }
    private JsonNode success(HttpResponse<byte[]> response) {
        JsonNode result = node(response);
        assertThat(result.path("code").intValue()).as("FG response: %s", result).isZero();
        return result.path("data");
    }
    private JsonNode history(Account reader, String table, long id, boolean root, long company) throws Exception {
        return ok(reader, "GET", "/api/data-logs?" + (root ? "rootTable=" : "table=") + table
                + (root ? "&rootId=" : "&rowId=") + id + "&limit=100", null, company);
    }
    private static JsonNode byTrace(JsonNode rows, String trace, String table) {
        var matches = rows.valueStream().filter(row -> row.path("requestId").asText().equals(trace) && row.path("table").asText().equals(table)).toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }
    private void assertTrace(HttpResponse<byte[]> response, String trace, long doc, long actor, int expectedFieldRows) {
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        var events = jdbc.queryForList("SELECT table_name,actor_user_id,client_ip,request_id,transaction_id FROM sys_data_log WHERE request_id=?", trace);
        assertThat(events).hasSize(expectedFieldRows).allSatisfy(row -> {
            assertThat(row.get("actor_user_id")).isEqualTo(actor);
            assertThat(row.get("client_ip")).isEqualTo("127.0.0.1");
            assertThat(row.get("request_id")).isEqualTo(trace);
        });
        assertThat(events.stream().map(row -> row.get("transaction_id")).distinct().count()).isOne();
        assertThat(jdbc.queryForObject("SELECT request_id FROM fg_stock_log WHERE fg_doc_id=? AND request_id=?", String.class, doc, trace)).isEqualTo(trace);
        assertThat(jdbc.queryForObject("SELECT request_id FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=? AND request_id=?", String.class, doc, trace)).isEqualTo(trace);
        assertThat(jdbc.queryForObject("SELECT success FROM sys_oper_log WHERE trace_id=?", Boolean.class, trace)).isTrue();
    }
    private static void assertChange(JsonNode row, String field, String old, String next) {
        assertThat(row.path("diff").path(field).path("old").asText()).isEqualTo(old);
        assertThat(row.path("diff").path(field).path("new").asText()).isEqualTo(next);
    }
    private void assertPersistedFields(String table, long id, Set<String> allowed) {
        List<String> fields = jdbc.queryForList("SELECT DISTINCT jsonb_object_keys(diff) FROM sys_data_log WHERE table_name=? AND row_id=?", String.class, table, id);
        assertThat(fields).isNotEmpty().allMatch(allowed::contains);
    }
    private List<Map<String, Object>> storedAudit(String table, long id, boolean root) {
        return jdbc.query("SELECT id,table_name,row_id,root_table,root_id,price_domain,action,diff,request_id,transaction_id FROM sys_data_log WHERE "
                + (root ? "root_table=? AND root_id=?" : "table_name=? AND row_id=?") + " ORDER BY id LIMIT 100",
                (rs, index) -> map("id", rs.getString("id"), "table", rs.getString("table_name"), "rowId", rs.getString("row_id"),
                        "rootTable", rs.getString("root_table"), "rootId", rs.getString("root_id"), "priceDomain", rs.getString("price_domain"),
                        "action", rs.getString("action"), "diff", json.readTree(rs.getString("diff")),
                        "requestId", rs.getString("request_id"), "transactionId", rs.getString("transaction_id")), table, id);
    }
    private static void assertStringIds(JsonNode rows) {
        for (var row : rows) {
            for (String field : List.of("id", "rowId", "rootId", "transactionId"))
                assertThat(row.path(field).isString()).as("%s", field).isTrue();
            if (row.hasNonNull("actorUserId")) assertThat(row.path("actorUserId").isString()).isTrue();
            for (var field : row.path("diff").properties()) {
                String key = field.getKey();
                if (key.equals("id") || key.endsWith("_id") || key.endsWith("_by")) {
                    for (String side : List.of("old", "new")) if (!field.getValue().path(side).isNull())
                        assertThat(field.getValue().path(side).isString()).as("%s.%s", key, side).isTrue();
                }
            }
        }
    }
    private void evidence(String filename, Object value) throws Exception {
        Path path = Path.of("test-artifacts/full-audit/fg/s4", filename);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
