package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.service.impl.TagAuditTargetGuard;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真 HTTP / PG 审计读取。SELF 的纯守卫检查单独标注，绝不冒充公开审计接口放权。 */
@Import(S4TagAuditHttpIT.AuditProbeConfig.class)
class S4TagAuditHttpIT extends A2BusinessFixture {
    @Autowired DeploymentNamespace namespace;
    @Autowired TagAuditTargetGuard guard;
    @Autowired AuditReadProbe probe;
    @Autowired PlatformTransactionManager transactions;
    private final List<Map<String, Object>> exchanges = new ArrayList<>();
    private static final Set<String> TAG_TABLES = Set.of("biz_tag", "biz_tag_object", "biz_tag_link");
    private record Target(String type, String table, long id, Long company) { }
    private record Child(String table, long id, Target parent) { }

    @AfterEach void archiveHttp(TestInfo info) throws Exception {
        RequestContextHolder.resetRequestAttributes();
        evidence(info.getTestMethod().orElseThrow().getName() + ".http.json", exchanges);
    }

    @Override HttpResponse<byte[]> raw(Account actor, String method, String path, Object body, Long company, String key) throws Exception {
        var response = super.raw(actor, method, path, body, company, key);
        record(method, path, company, response);
        return response;
    }

    @Test void allTwelveRootsAndAssociationIdsRequireActualReadPermissionBeforeEveryPage() throws Exception {
        Fixture f = fixture();
        Account owner = domainActor(f.company(), "SELF", false);
        Account noDomain = custom(f.company(), "ALL", "system:audit:query", "biz:tag:list");
        Account selfAuditor = domainActor(f.company(), "SELF", true);
        List<Object> results = new ArrayList<>();
        for (Target target : targets(f, owner)) {
            Account auditor = custom(f.company(), "ALL", "system:audit:query", readPermission(target));
            JsonNode before = history(auditor, target.table(), target.id(), true, target.company());
            assertThat(registries(target)).isZero();
            String trace = trace("replace");
            JsonNode saved = success(traced(owner, "POST", objectPath(target) + "/replace",
                    replacement(0, List.of("audit-" + suffix()), Map.of(), List.of()), target.company(), UUID.randomUUID().toString(), trace));
            long object = registry(target), link = link(object, id(saved.path("tags").get(0).path("id")));
            JsonNode roots = history(auditor, target.table(), target.id(), true, target.company());
            JsonNode objectRows = history(auditor, "biz_tag_object", object, false, target.company());
            JsonNode links = history(auditor, "biz_tag_link", link, false, target.company());
            for (JsonNode rows : List.of(roots, objectRows, links)) assertIds(rows);
            assertThat(objectRows.valueStream().map(row -> row.path("action").asText()).toList()).contains("INSERT", "UPDATE");
            JsonNode version = objectRows.valueStream().filter(row -> row.path("action").asText().equals("UPDATE")).findFirst().orElseThrow();
            assertChange(version, "version", "0", "1");
            assertThat(links.get(0).path("rootTable").asText()).isEqualTo(target.table());
            assertThat(links.get(0).path("rootId").asText()).isEqualTo(Long.toString(target.id()));
            List<Object> denials = new ArrayList<>();
            for (String path : List.of(auditPath(target.table(), target.id(), true), auditPath("biz_tag_object", object, false), auditPath("biz_tag_link", link, false))) {
                JsonNode denied = call(noDomain, "GET", path, null, target.company());
                assertThat(denied.path("code").intValue()).isIn(403, 404);
                assertThat(call(noDomain, "GET", path + "&beforeId=1", null, target.company()).path("code").intValue()).isIn(403, 404);
                assertThat(call(selfAuditor, "GET", path, null, target.company()).path("code").intValue()).isEqualTo(403);
                assertThat(call(owner, "GET", path, null, target.company()).path("code").intValue()).isEqualTo(403);
                if (target.company() != null) {
                    assertThat(call(auditor, "GET", path, null, null).path("code").intValue()).isEqualTo(400);
                    assertThat(call(auditor, "GET", path, null, f.otherCompany()).path("code").intValue()).isIn(403, 404);
                }
                denials.add(map("path", path, "noDomain", denied));
            }
            List<Map<String, Object>> stored = stored(trace);
            assertThat(stored).hasSize(4).allSatisfy(row -> {
                assertThat(row.get("actorUserId")).isEqualTo(Long.toString(owner.id()));
                assertThat(row.get("requestId")).isEqualTo(trace);
            });
            assertThat(stored.stream().map(row -> row.get("transactionId")).distinct().count()).isOne();
            List<Map<String, Object>> timeline = timeline(trace), operations = operations(trace);
            assertThat(timeline).hasSize(1); assertThat(operations).hasSize(1);
            assertThat(timeline.getFirst().get("operator_id")).isEqualTo(Long.toString(owner.id()));
            assertThat(operations.getFirst().get("success")).isEqualTo(true);
            assertThat(registries(target)).isEqualTo(1);
            results.add(map("type", target.type(), "actualReaderPermissions", permissions.loadPermissions(auditor.id()).stream().sorted().toList(),
                    "readBeforeRegistration", before, "rootHistory", roots, "objectHistory", objectRows,
                    "linkHistory", links, "denials", denials, "storedAudit", stored, "timeline", timeline, "operations", operations));
        }
        evidence("twelve-targets-http-and-sql.json", results);
    }

    @Test void pureGuardRetainsRealSelfScopeWhilePublicHttpStillRequiresAllScope() throws Exception {
        Fixture f = fixture();
        Account owner = domainActor(f.company(), "SELF", false), outsider = domainActor(f.company(), "SELF", false);
        List<Object> results = new ArrayList<>();
        for (Target target : targets(f, owner)) {
            JsonNode saved = ok(owner, "POST", objectPath(target) + "/replace",
                    replacement(0, List.of("scope-" + suffix()), Map.of(), List.of()), target.company());
            long object = registry(target), link = link(object, id(saved.path("tags").get(0).path("id")));
            authenticate(owner);
            withCompany(target.company(), () -> guard.check("biz_tag_link", link));
            authenticate(outsider);
            if (target.company() == null) withCompany(null, () -> guard.check("biz_tag_object", object));
            else assertThatThrownBy(() -> withCompany(target.company(), () -> guard.check("biz_tag_object", object)))
                    .isInstanceOf(BizException.class).extracting(failure -> ((BizException) failure).getCode()).isEqualTo(404);
            results.add(map("type", target.type(), "evidenceLayer", "actual PostgreSQL + direct guard, not public HTTP",
                    "ownerAccepted", true, "outsiderAccepted", target.company() == null));
        }
        evidence("self-scope-direct-guard.json", results);
    }

    @Test void libraryRenameArchiveAndLongIdsRemainAuditableWithoutOpeningArbitraryObjects() throws Exception {
        Fixture f = fixture(); Account maker = domainActor(f.company(), "SELF", false);
        Account manager = custom(f.company(), "ALL", "biz:tag:manage", "biz:tag:list");
        Account libraryAuditor = custom(f.company(), "ALL", "system:audit:query", "biz:tag:list");
        Account noLibrary = custom(f.company(), "ALL", "system:audit:query");
        String name = "large-" + suffix(), changed = name + "-new";
        long tag = 9_007_199_254_740_993L + Long.parseLong(suffix().substring(0, 9), 16);
        jdbc.update("INSERT INTO biz_tag(id,tenant_key,name,canonical) OVERRIDING SYSTEM VALUE VALUES(?,?,?,?)", tag, namespace.tenant(), name, name);
        Target target = new Target("STYLE", "sty_style", f.style(), null);
        ok(maker, "POST", objectPath(target) + "/replace", replacement(0, List.of(name), Map.of(), List.of()), null);
        String renameTrace = trace("rename"), archiveTrace = trace("archive");
        success(traced(manager, "POST", "/api/tags/" + tag + "/rename", map("version", 0, "name", changed), null, UUID.randomUUID().toString(), renameTrace));
        success(traced(manager, "POST", "/api/tags/" + tag + "/archive", map("version", 1), null, UUID.randomUUID().toString(), archiveTrace));
        JsonNode rows = history(libraryAuditor, "biz_tag", tag, false, null);
        assertIds(rows);
        assertChange(byTrace(rows, renameTrace, "biz_tag"), "name", name, changed);
        assertChange(byTrace(rows, archiveTrace, "biz_tag"), "deleted_token", "0", Long.toString(tag));
        JsonNode root = history(libraryAuditor, "biz_tag", tag, true, f.otherCompany());
        assertThat(root).isEqualTo(rows); // 字典跨公司共享，读字典不获得对象关联。
        assertThat(call(noLibrary, "GET", auditPath("biz_tag", tag, false), null, null).path("code").intValue()).isEqualTo(403);
        assertThat(call(noLibrary, "GET", auditPath("biz_tag", tag, false) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(403);
        assertThat(call(libraryAuditor, "GET", auditPath("biz_tag_object", registry(target), false), null, null).path("code").intValue()).isEqualTo(403);
        JsonNode logs = ok(manager, "GET", "/api/tags/" + tag + "/logs", null, null);
        assertThat(logs.path("list").valueStream().map(row -> row.path("action").asText()).toList()).containsExactly("archive", "rename");
        assertThat(call(manager, "GET", "/api/tags/objects/PUBLIC_TAG/" + tag, null, null).path("code").intValue()).isEqualTo(400);
        for (String suffix : List.of("/attachments", "/references")) {
            assertThat(call(manager, "GET", "/api/tags/" + tag + suffix, null, null).path("code").intValue()).isEqualTo(404);
        }
        for (String trace : List.of(renameTrace, archiveTrace)) {
            assertThat(timeline(trace)).hasSize(1).allSatisfy(row -> assertThat(row.get("doc_type")).isEqualTo("PUBLIC_TAG"));
            assertThat(operations(trace)).hasSize(1).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        }
        long auditId = id(rows.get(0).path("id"));
        for (String mutation : List.of("UPDATE sys_data_log SET diff='{}' WHERE id=" + auditId, "DELETE FROM sys_data_log WHERE id=" + auditId))
            assertThatThrownBy(() -> jdbc.execute(mutation)).isInstanceOf(DataAccessException.class);
        evidence("library-archived-long-id.json", map("history", rows, "publicTimeline", logs, "renameStored", stored(renameTrace),
                "archiveStored", stored(archiveTrace), "renameOperation", operations(renameTrace), "archiveOperation", operations(archiveTrace)));
    }

    @Test void removedLinkHistoryKeepsRealUpdateDeleteIdentitiesAndCursorDoesNotSkipAuthorization() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Target target = new Target("STYLE", "sty_style", f.style(), null);
        String first = "first-" + suffix(), second = "second-" + suffix();
        JsonNode saved = ok(owner, "POST", objectPath(target) + "/replace", replacement(0, List.of(first, second), Map.of(), List.of()), null);
        long a = id(saved.path("tags").get(0).path("id")), b = id(saved.path("tags").get(1).path("id")), object = registry(target);
        long linkA = link(object, a), linkB = link(object, b);
        String moveTrace = trace("reorder");
        JsonNode reordered = success(traced(owner, "POST", objectPath(target) + "/replace", replacement(1, List.of(second, first), versions(saved), List.of(b, a)), null, UUID.randomUUID().toString(), moveTrace));
        assertThat(link(object, a)).isEqualTo(linkA); assertThat(link(object, b)).isEqualTo(linkB);
        JsonNode moved = byTrace(history(reader, "biz_tag_link", linkA, false, null), moveTrace, "biz_tag_link");
        assertThat(moved.path("action").asText()).isEqualTo("UPDATE"); assertChange(moved, "seq", "1", "2");
        String deleteTrace = trace("unlink");
        success(traced(owner, "POST", objectPath(target) + "/replace", replacement(2, List.of(second), versions(reordered), List.of(b)), null, UUID.randomUUID().toString(), deleteTrace));
        JsonNode history = history(reader, "biz_tag_link", linkA, false, null);
        assertThat(history.get(0).path("action").asText()).isEqualTo("DELETE");
        assertThat(history.get(0).path("diff").path("tag_id").path("old").asText()).isEqualTo(Long.toString(a));
        JsonNode older = ok(reader, "GET", auditPath("biz_tag_link", linkA, false) + "&beforeId=" + history.get(0).path("id").asText(), null, null);
        assertThat(older.valueStream().map(row -> row.path("action").asText()).toList()).containsExactly("UPDATE", "INSERT");
        assertThat(call(reader, "GET", auditPath("biz_tag_link", Long.MAX_VALUE, false), null, null).path("code").intValue()).isEqualTo(404);
        assertThat(call(reader, "GET", auditPath("biz_tag_link", linkA, false) + "&rootTable=sty_style&rootId=" + f.style(), null, null).path("code").intValue()).isEqualTo(400);
        evidence("link-identity-cursor.json", map("reorder", moved, "deletedHistory", history, "olderPage", older, "storedDelete", stored(deleteTrace)));
    }

    @Test void fourteenExistingChildTablesCannotBypassTheirRealParentWithDirectIdsOrEmptyCursors() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Account noDomain = custom(f.company(), "ALL", "system:audit:query", FieldPermissions.COST, FieldPermissions.ORDER, FieldPermissions.PRODUCTION);
        Account self = domainActor(f.company(), "SELF", true);
        List<Target> parents = targets(f, owner);
        List<Child> children = children(f, owner, parents);
        assertThat(children).hasSize(14);
        List<Object> results = new ArrayList<>();
        for (Child child : children) {
            String path = auditPath(child.table(), child.id(), false);
            JsonNode rows = history(reader, child.table(), child.id(), false, child.parent().company());
            assertThat(rows.isEmpty()).isFalse();
            assertThat(rows.valueStream().map(row -> row.path("rootTable").asText()).distinct().toList()).containsExactly(child.parent().table());
            assertThat(rows.valueStream().map(row -> row.path("rootId").asText()).distinct().toList()).containsExactly(Long.toString(child.parent().id()));
            assertThat(call(noDomain, "GET", path, null, child.parent().company()).path("code").intValue()).isEqualTo(403);
            assertThat(call(noDomain, "GET", path + "&beforeId=1", null, child.parent().company()).path("code").intValue()).isEqualTo(403);
            assertThat(call(self, "GET", path, null, child.parent().company()).path("code").intValue()).isEqualTo(403);
            if (child.parent().company() != null) {
                assertThat(call(reader, "GET", path, null, null).path("code").intValue()).isEqualTo(400);
                assertThat(call(reader, "GET", path, null, f.otherCompany()).path("code").intValue()).isEqualTo(403);
            }
            results.add(map("table", child.table(), "rowId", Long.toString(child.id()), "history", rows));
        }
        Child cost = children.stream().filter(child -> child.table().equals("tp_cost_item")).findFirst().orElseThrow();
        jdbc.update("DELETE FROM tp_cost_item WHERE id=?", cost.id());
        JsonNode removedChild = history(reader, cost.table(), cost.id(), false, null);
        assertThat(removedChild.get(0).path("action").asText()).isEqualTo("DELETE");
        Child inventory = children.stream().filter(child -> child.table().equals("inv_doc_item")).findFirst().orElseThrow();
        jdbc.update("DELETE FROM inv_doc_item WHERE id=?", inventory.id());
        assertThat(history(reader, inventory.table(), inventory.id(), false, f.company()).get(0).path("action").asText()).isEqualTo("DELETE");
        jdbc.update("DELETE FROM inv_doc WHERE id=?", inventory.parent().id());
        JsonNode parentGone = call(reader, "GET", auditPath(inventory.table(), inventory.id(), false), null, f.company());
        assertThat(parentGone.path("code").intValue()).isEqualTo(404);
        assertThat(call(reader, "GET", auditPath("tp_cost_item", Long.MAX_VALUE, false) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(404);
        evidence("fourteen-child-parent-guards.json", map("children", results, "removedChildWithLiveParent", removedChild, "removedParentDenied", parentGone));
    }

    @Test void deletedFgRootAndTagLinksReuseOnlyTheRealExistingFgHistoricalProof() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Account noFg = custom(f.company(), "ALL", "system:audit:query", "biz:tag:list");
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,'Audit FG','PRODUCT') RETURNING id", Long.class, "TGA" + suffix());
        long doc = id(ok(owner, "POST", "/api/fg-docs", map("companyId", f.company(), "docType", "OTHER_IN", "warehouseId", warehouse,
                "docDate", today(), "reason", "OTHER", "items", List.of(map("styleSkuId", f.styleSku(), "qty", 1))), f.company()).path("id"));
        long item = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc);
        Target target = new Target("FG_DOC", "fg_doc", doc, f.company());
        JsonNode tagged = ok(owner, "POST", objectPath(target) + "/replace", replacement(0, List.of("deleted-fg-" + suffix()), Map.of(), List.of()), f.company());
        long object = registry(target), link = link(object, id(tagged.path("tags").get(0).path("id")));
        success(traced(owner, "DELETE", "/api/fg-docs/" + doc + "?companyId=" + f.company() + "&version=0", null, f.company(), UUID.randomUUID().toString(), trace("delete-fg")));
        assertThat(registries(target)).isZero();
        List<Object> results = new ArrayList<>();
        for (String path : List.of(auditPath("fg_doc", doc, true), auditPath("fg_doc_item", item, false),
                auditPath("biz_tag_object", object, false), auditPath("biz_tag_link", link, false))) {
            JsonNode history = ok(reader, "GET", path, null, f.company());
            assertThat(history.isEmpty()).isFalse();
            assertThat(call(reader, "GET", path, null, f.otherCompany()).path("code").intValue()).isEqualTo(404);
            assertThat(call(reader, "GET", path, null, null).path("code").intValue()).isEqualTo(400);
            assertThat(call(noFg, "GET", path, null, f.company()).path("code").intValue()).isIn(403, 404);
            results.add(map("path", path, "history", history));
        }
        // 非 FG 父头没有同等历史归属证明，不能借普通 tag DELETE 放行。
        Target style = new Target("STYLE", "sty_style", emptyStyle(f), null);
        JsonNode styleTags = ok(owner, "POST", objectPath(style) + "/replace", replacement(0, List.of("deleted-style-" + suffix()), Map.of(), List.of()), null);
        long styleObject = registry(style), styleLink = link(styleObject, id(styleTags.path("tags").get(0).path("id")));
        jdbc.update("DELETE FROM sty_style WHERE id=?", style.id());
        assertThat(call(reader, "GET", auditPath("sty_style", style.id(), true), null, null).path("code").intValue()).isEqualTo(404);
        assertThat(call(reader, "GET", auditPath("biz_tag_link", styleLink, false), null, null).path("code").intValue()).isEqualTo(404);
        evidence("deleted-fg-proof.json", results);
    }

    @Test void replayNeverDuplicatesFieldOrBusinessAuditAndEveryReadRechecksCurrentCompany() throws Exception {
        Fixture f = fixture(); Account maker = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Target target = new Target("ORDER", "ord_order", sourceOrder(f, maker, "DRAFT", f.company()), f.company());
        String key = UUID.randomUUID().toString(), firstTrace = trace("first"), replayTrace = trace("replay");
        Object body = replacement(0, List.of("replayed-" + suffix()), Map.of(), List.of());
        JsonNode first = success(traced(maker, "POST", objectPath(target) + "/replace", body, f.company(), key, firstTrace));
        JsonNode replay = success(traced(maker, "POST", objectPath(target) + "/replace", body, f.company(), key, replayTrace));
        assertThat(replay).isEqualTo(first);
        assertThat(stored(replayTrace)).isEmpty(); assertThat(timeline(replayTrace)).isEmpty();
        assertThat(operations(replayTrace)).hasSize(1).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        long object = registry(target);
        jdbc.update("UPDATE ord_order SET company_id=? WHERE id=?", f.otherCompany(), target.id());
        JsonNode refusedReplay = node(traced(maker, "POST", objectPath(target) + "/replace", body, f.company(), key, trace("old-company")));
        assertThat(refusedReplay.path("code").intValue()).isEqualTo(403);
        assertThat(call(reader, "GET", auditPath("biz_tag_object", object, false), null, f.company()).path("code").intValue()).isEqualTo(403);
        JsonNode correctCompany = history(reader, "biz_tag_object", object, false, f.otherCompany());
        assertThat(correctCompany.get(0).path("diff").path("company_id").path("new").asText()).isEqualTo(Long.toString(f.otherCompany()));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='order:order:list')", reader.role());
        // P12：旧会话已撤销，应先 401；重新登录后继续验证领域守卫的 403，不能只放宽旧断言。
        assertThat(call(reader, "GET", auditPath("biz_tag_object", object, false), null, f.otherCompany()).path("code").intValue()).isEqualTo(401);
        assertThat(call(reader, "GET", auditPath("ord_order", target.id(), true) + "&beforeId=1", null, f.otherCompany()).path("code").intValue()).isEqualTo(401);
        String username = jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, reader.id());
        Account currentReader = new Account(reader.id(), reader.role(), ok(null, "POST", "/api/auth/login",
                map("username", username, "password", "A2-Business-123456"), null).asText());
        assertThat(call(currentReader, "GET", auditPath("biz_tag_object", object, false), null, f.otherCompany()).path("code").intValue()).isEqualTo(403);
        assertThat(call(currentReader, "GET", auditPath("ord_order", target.id(), true) + "&beforeId=1", null, f.otherCompany()).path("code").intValue()).isEqualTo(403);
        evidence("replay-and-current-access.json", map("first", first, "replay", replay, "firstStored", stored(firstTrace),
                "replayStored", stored(replayTrace), "replayTimeline", timeline(replayTrace), "replayOperation", operations(replayTrace),
                "oldCompanyReplay", refusedReplay, "newCompanyHistory", correctCompany));
    }

    @Test void realCompanyMoveDuringReadKeepsOneAuthorizedSnapshot() throws Exception {
        Fixture f = fixture(); Account maker = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Target target = new Target("ORDER", "ord_order", sourceOrder(f, maker, "DRAFT", f.company()), f.company());
        ok(maker, "POST", objectPath(target) + "/replace", replacement(0, List.of("snapshot-" + suffix()), Map.of(), List.of()), f.company());
        Gate gate = new Gate(target.table(), target.id());
        probe.gates.put(gate.key, gate);
        String readTrace = trace("snapshot"), writeTrace = trace("move-company");
        Map<String, Object> facts = map("rootTable", target.table(), "rootId", Long.toString(target.id()),
                "readCompanyHeader", Long.toString(f.company()), "newCompany", Long.toString(f.otherCompany()));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = workers.submit(() -> traced(reader, "GET", auditPath(target.table(), target.id(), true), null,
                    f.company(), null, readTrace, gate.key));
            try {
                assertThat(gate.ready.await(15, TimeUnit.SECONDS)).as("real domain guard reached the PostgreSQL barrier").isTrue();
                facts.put("readerConnection", gate.reader);
                Map<String, Object> writer = new TransactionTemplate(transactions).execute(status -> {
                    Map<String, Object> actual = jdbc.queryForMap("SELECT pg_backend_pid() AS pid,current_setting('transaction_isolation') AS isolation,txid_current()::text AS transaction_id");
                    jdbc.queryForObject("SELECT set_config('scm.audit_request',?,true)", String.class, writeTrace);
                    assertThat(jdbc.update("UPDATE ord_order SET company_id=? WHERE id=? AND company_id=?", f.otherCompany(), target.id(), f.company())).isOne();
                    return actual;
                });
                facts.put("writerConnection", writer);
                long committed = jdbc.queryForObject("SELECT company_id FROM ord_order WHERE id=?", Long.class, target.id());
                facts.put("committedCompanyBeforeReadResumed", Long.toString(committed));
                gate.resume.countDown();
                JsonNode actualResponse = node(response.get(25, TimeUnit.SECONDS));
                facts.put("actualHttpResponse", actualResponse);
                facts.put("writerStoredEvents", stored(writeTrace));
                boolean sawCompanyB = actualResponse.path("data").valueStream()
                        .anyMatch(row -> row.path("requestId").asText().equals(writeTrace));
                facts.put("responseSawCommittedCompanyBEvents", sawCompanyB);
                // 红色对照也先保留完整两连接/实际响应；不在 UPDATE 前因隔离级别不同提前结束。
                evidence("company-snapshot-interleaving.json", facts);
                assertThat(writer).isNotNull();
                assertThat(writer.get("pid")).isNotEqualTo(gate.reader.get("pid"));
                assertThat(committed).isEqualTo(f.otherCompany());
                assertThat(actualResponse.path("code").intValue()).isZero();
                assertThat(sawCompanyB).as("a company A audit snapshot must not contain new company B events").isFalse();
                assertThat(gate.reader.get("isolation")).isEqualTo("repeatable read");
                assertThat(actualResponse.path("data").valueStream().filter(row -> row.path("diff").has("company_id"))
                        .allMatch(row -> !row.path("diff").path("company_id").path("new").asText().equals(Long.toString(f.otherCompany())))).isTrue();
                assertThat(call(reader, "GET", auditPath(target.table(), target.id(), true), null, f.company()).path("code").intValue()).isEqualTo(403);
                assertThat(history(reader, target.table(), target.id(), true, f.otherCompany()).valueStream()
                        .anyMatch(row -> row.path("requestId").asText().equals(writeTrace))).isTrue();
            } finally {
                gate.resume.countDown();
                probe.gates.remove(gate.key);
                if (!response.isDone()) response.cancel(true);
                evidence("company-snapshot-interleaving.json", facts);
            }
        }
    }

    @Test void failingRealTimelineRollsBackTagsFieldDiffAndReceiptWhileRetainingSafeFailureLog() throws Exception {
        Fixture f = fixture(); Account maker = domainActor(f.company(), "SELF", false), reader = domainActor(f.company(), "ALL", true);
        Target target = new Target("MATERIAL", "bas_material", f.material(), null);
        String marker = "s4_tag_fail_" + suffix(), name = "rollback-" + suffix(), trace = trace("rollback"), key = UUID.randomUUID().toString();
        jdbc.execute("CREATE FUNCTION " + marker + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.doc_type='MATERIAL' AND NEW.doc_id=" + f.material()
                + " AND NEW.action='replace-tags' THEN RAISE EXCEPTION 'private tag rollback detail'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " BEFORE INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + marker + "()");
        JsonNode failed;
        try {
            failed = node(traced(maker, "POST", objectPath(target) + "/replace", replacement(0, List.of(name), Map.of(), List.of()), null, key, trace));
            assertThat(failed.path("code").intValue()).isEqualTo(500);
            assertThat(failed.toString()).doesNotContain("private tag rollback detail", "biz_doc_log", "SQLException", "stack");
        } finally {
            jdbc.execute("DROP TRIGGER " + marker + " ON biz_doc_log"); jdbc.execute("DROP FUNCTION " + marker + "()");
        }
        var counts = map("tagRows", jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=?", Long.class, namespace.tenant(), name),
                "registryRows", registries(target), "fieldAuditRows", stored(trace).size(), "businessAuditRows", timeline(trace).size(),
                "idempotencyReceiptRows", jdbc.queryForObject("SELECT count(*) FROM biz_http_action WHERE tenant_code=? AND user_id=? AND operation='TAG_REPLACE' AND request_key=?::uuid", Long.class, namespace.tenant(), maker.id(), key));
        assertThat(counts.values()).allSatisfy(value -> assertThat(((Number) value).longValue()).isZero());
        List<Map<String, Object>> failureLog = operations(trace);
        assertThat(failureLog).hasSize(1); assertThat(failureLog.getFirst().get("success")).isEqualTo(false);
        assertThat(history(reader, target.table(), target.id(), true, null).valueStream().filter(row -> TAG_TABLES.contains(row.path("table").asText())).count()).isZero();
        evidence("rollback-real-http-and-counts.json", map("httpResponse", failed, "actualCounts", counts, "failureOperation", failureLog,
                "fieldAudit", stored(trace), "timeline", timeline(trace)));
    }

    private Account domainActor(long company, String scope, boolean audit) throws Exception {
        var grants = new ArrayList<>(List.of("biz:tag:list", "style:style:list", "style:style:edit", "basic:material:list", "basic:material:edit",
                "supplier:supplier:list", "supplier:supplier:edit", "techpack:pack:list", "techpack:pack:edit", "order:order:list", "order:order:edit",
                "purchase:mrp:list", "purchase:mrp:edit", "purchase:order:list", "purchase:order:edit", "inventory:doc:list", "inventory:doc:edit",
                "inventory:count:list", "inventory:count:edit", "production:order:list", "production:order:edit", "production:receipt:add",
                "fg:doc:list", "fg:doc:edit", "fg:doc:add", "fg:doc:remove"));
        if (audit) grants.add("system:audit:query");
        return custom(company, scope, grants.toArray(String[]::new));
    }

    private static String readPermission(Target target) {
        return switch (target.type()) {
            case "STYLE" -> "style:style:list";
            case "MATERIAL" -> "basic:material:list";
            case "SUPPLIER" -> "supplier:supplier:list";
            case "TECH_PACK" -> "techpack:pack:list";
            case "ORDER" -> "order:order:list";
            case "MRP_CALC" -> "purchase:mrp:list";
            case "PURCHASE_ORDER" -> "purchase:order:list";
            case "INV_DOC" -> "inventory:doc:list";
            case "INV_COUNT" -> "inventory:count:list";
            case "PRODUCTION_ORDER", "PRODUCTION_RECEIPT" -> "production:order:list";
            case "FG_DOC" -> "fg:doc:list";
            default -> throw new IllegalArgumentException("unexpected test target");
        };
    }

    private List<Target> targets(Fixture f, Account actor) {
        long order = sourceOrder(f, actor, "DRAFT", f.company()), source = sourceOrder(f, actor, "APPROVED", f.company());
        long purchase = sourcePurchase(f, actor); jdbc.update("UPDATE pur_order SET status='DRAFT' WHERE id=?", purchase);
        long production = sourceProduction(f, actor, source, "DRAFT"), receiptParent = sourceProduction(f, actor, source, "APPROVED");
        long receipt = jdbc.queryForObject("INSERT INTO prd_receipt(no,company_id,prd_order_id,receipt_date,status,create_by) VALUES(?,?,?,CURRENT_DATE,'APPROVED',?) RETURNING id", Long.class, "TAHH" + suffix(), f.company(), receiptParent, actor.id());
        long mrp = jdbc.queryForObject("INSERT INTO mrp_calc(no,company_id,status,create_by) VALUES(?,?,'CONFIRMED',?) RETURNING id", Long.class, "TAMRP" + suffix(), f.company(), actor.id());
        jdbc.update("INSERT INTO mrp_calc_order(calc_id,order_id) VALUES(?,?)", mrp, source);
        long inv = jdbc.queryForObject("INSERT INTO inv_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, "TAINV" + suffix(), f.company(), f.warehouse(), actor.id());
        long count = jdbc.queryForObject("INSERT INTO inv_count(no,company_id,warehouse_id,count_date,counter_id,status,create_by) VALUES(?,?,?,CURRENT_DATE,?,'DRAFT',?) RETURNING id", Long.class, "TACNT" + suffix(), f.company(), f.warehouse(), actor.id(), actor.id());
        long pack = jdbc.queryForObject("INSERT INTO tp_tech_pack(style_id,stage,version,status) VALUES(?,'PROD',2,'DRAFT') RETURNING id", Long.class, f.style());
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,'Tag audit finished goods','PRODUCT') RETURNING id", Long.class, "TAW" + suffix());
        long fg = jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, "TAFG" + suffix(), f.company(), warehouse, actor.id());
        return List.of(new Target("STYLE", "sty_style", f.style(), null), new Target("MATERIAL", "bas_material", f.material(), null),
                new Target("SUPPLIER", "sup_supplier", f.supplier(), null), new Target("TECH_PACK", "tp_tech_pack", pack, null),
                new Target("ORDER", "ord_order", order, f.company()), new Target("MRP_CALC", "mrp_calc", mrp, f.company()),
                new Target("PURCHASE_ORDER", "pur_order", purchase, f.company()), new Target("INV_DOC", "inv_doc", inv, f.company()),
                new Target("INV_COUNT", "inv_count", count, f.company()), new Target("PRODUCTION_ORDER", "prd_order", production, f.company()),
                new Target("PRODUCTION_RECEIPT", "prd_receipt", receipt, f.company()), new Target("FG_DOC", "fg_doc", fg, f.company()));
    }

    private long emptyStyle(Fixture f) {
        return jdbc.queryForObject("INSERT INTO sty_style(code,name,style_class,brand,year,season,source,category_id,size_group_id,status) VALUES(?,'Tag audit empty','FS','TA',2026,'AUTUMN','OWN',?,?,'DRAFT') RETURNING id", Long.class, "TAS" + suffix(), f.category(), f.sizeGroup());
    }

    private List<Child> children(Fixture f, Account owner, List<Target> parents) {
        Target material = parent(parents, "MATERIAL"), supplier = parent(parents, "SUPPLIER"), order = parent(parents, "ORDER");
        Target purchase = parent(parents, "PURCHASE_ORDER"), production = parent(parents, "PRODUCTION_ORDER");
        Target inv = parent(parents, "INV_DOC"), count = parent(parents, "INV_COUNT");
        Target pack = new Target("TECH_PACK", "tp_tech_pack", f.pack(), null);
        long supplierChange = jdbc.queryForObject("INSERT INTO sup_supplier_change(supplier_id,changes,apply_by,apply_time) VALUES(?,'[{\"field\":\"name\",\"before\":\"before\",\"after\":\"after\"}]'::jsonb,?,now()) RETURNING id", Long.class, f.supplier(), owner.id());
        long bomSku = jdbc.queryForObject("SELECT id FROM tp_bom_item_sku WHERE bom_item_id=?", Long.class, f.bom());
        long bomSize = jdbc.queryForObject("INSERT INTO tp_bom_item_size(bom_item_id,size_code,usage_qty) VALUES(?,'M',1.5) RETURNING id", Long.class, f.bom());
        long process = jdbc.queryForObject("INSERT INTO tp_process(tech_pack_id,seq,name,price) VALUES(?,1,'Tag audit seam',1.25) RETURNING id", Long.class, f.pack());
        long cost = jdbc.queryForObject("INSERT INTO tp_cost_item(tech_pack_id,seq,name,amount) VALUES(?,1,'Tag audit cost',1.25) RETURNING id", Long.class, f.pack());
        long orderItem = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, order.id());
        long change = jdbc.queryForObject("INSERT INTO ord_change(no,company_id,order_id,change_types,reason,create_by) VALUES(?,?,?,'QTY','Audit change fixture',?) RETURNING id", Long.class, "TAGCH" + suffix(), f.company(), order.id(), owner.id());
        long changeItem = jdbc.queryForObject("INSERT INTO ord_change_item(change_id,seq,change_type,order_item_id,from_qty,to_qty) VALUES(?,1,'QTY',?,10,9) RETURNING id", Long.class, change, orderItem);
        long purchaseItem = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase.id());
        long productionItem = jdbc.queryForObject("SELECT id FROM prd_order_item WHERE prd_order_id=?", Long.class, production.id());
        long invItem = jdbc.queryForObject("INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty) VALUES(?,1,?,1) RETURNING id", Long.class, inv.id(), f.materialSku());
        long countItem = jdbc.queryForObject("INSERT INTO inv_count_item(count_id,seq,material_sku_id,book_qty,counted_qty) VALUES(?,1,?,0,1) RETURNING id", Long.class, count.id(), f.materialSku());
        return List.of(new Child("bas_material_sku", f.materialSku(), material), new Child("sup_supplier_change", supplierChange, supplier),
                new Child("tp_bom_item", f.bom(), pack), new Child("tp_bom_item_sku", bomSku, pack), new Child("tp_bom_item_size", bomSize, pack),
                new Child("tp_process", process, pack), new Child("tp_cost_item", cost, pack), new Child("ord_order_item", orderItem, order),
                new Child("ord_change", change, order), new Child("ord_change_item", changeItem, order), new Child("pur_order_item", purchaseItem, purchase),
                new Child("prd_order_item", productionItem, production), new Child("inv_doc_item", invItem, inv), new Child("inv_count_item", countItem, count));
    }

    private static Target parent(List<Target> values, String type) {
        return values.stream().filter(value -> value.type().equals(type)).findFirst().orElseThrow();
    }
    private long registry(Target target) { return jdbc.queryForObject("SELECT id FROM biz_tag_object WHERE tenant_key=? AND object_type=? AND object_id=?", Long.class, namespace.tenant(), target.type(), target.id()); }
    private long registries(Target target) { return jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE tenant_key=? AND object_type=? AND object_id=?", Long.class, namespace.tenant(), target.type(), target.id()); }
    private long link(long object, long tag) { return jdbc.queryForObject("SELECT id FROM biz_tag_link WHERE tenant_key=? AND object_ref_id=? AND tag_id=?", Long.class, namespace.tenant(), object, tag); }
    private static String objectPath(Target target) { return "/api/tags/objects/" + target.type() + "/" + target.id(); }
    private static String auditPath(String table, long id, boolean root) { return "/api/data-logs?" + (root ? "rootTable=" : "table=") + table + (root ? "&rootId=" : "&rowId=") + id + "&limit=100"; }
    private JsonNode history(Account actor, String table, long id, boolean root, Long company) throws Exception { return ok(actor, "GET", auditPath(table, id, root), null, company); }
    private static Map<String, Object> replacement(int version, List<String> names, Map<String, Integer> versions, List<Long> retained) {
        return map("version", version, "names", names, "tagVersions", versions, "retainIds", retained.stream().map(String::valueOf).toList());
    }
    private static Map<String, Integer> versions(JsonNode object) {
        Map<String, Integer> result = new LinkedHashMap<>();
        object.path("tags").forEach(tag -> result.put(tag.path("id").asText(), tag.path("version").intValue()));
        return result;
    }
    private static void withCompany(Long company, Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data-logs");
        if (company != null) request.addHeader("X-Company-Id", company.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { action.run(); } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private static String trace(String action) { return "s4-tag-" + action + "-" + UUID.randomUUID(); }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, Long company, String key, String trace) throws Exception {
        return traced(actor, method, path, body, company, key, trace, null);
    }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, Long company, String key, String trace, String probeKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json").header("X-Request-Id", trace);
        if (company != null) request.header("X-Company-Id", company.toString());
        if (key != null) request.header("Idempotency-Key", key);
        if (probeKey != null) request.header("X-S4-Audit-Probe", probeKey);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        record(method, path, company, response);
        return response;
    }
    private void record(String method, String path, Long company, HttpResponse<byte[]> response) {
        if (path.startsWith("/api/auth/")) return;
        exchanges.add(map("method", method, "path", path, "companyHeader", company == null ? null : company.toString(),
                "httpStatus", response.statusCode(), "requestId", response.headers().firstValue("X-Request-Id").orElse(null), "response", node(response)));
    }
    private JsonNode success(HttpResponse<byte[]> response) {
        JsonNode value = node(response); assertThat(value.path("code").intValue()).as(value.toString()).isZero(); return value.path("data");
    }
    private static JsonNode byTrace(JsonNode rows, String trace, String table) {
        var selected = rows.valueStream().filter(row -> row.path("requestId").asText().equals(trace) && row.path("table").asText().equals(table)).toList();
        assertThat(selected).hasSize(1); return selected.getFirst();
    }
    private static void assertChange(JsonNode row, String field, String old, String next) {
        assertThat(row.path("diff").path(field).path("old").asText()).isEqualTo(old);
        assertThat(row.path("diff").path(field).path("new").asText()).isEqualTo(next);
    }
    private static void assertIds(JsonNode rows) {
        for (JsonNode row : rows) {
            for (String field : List.of("id", "rowId", "rootId", "transactionId")) assertThat(row.path(field).isString()).as(field).isTrue();
            for (var field : row.path("diff").properties()) {
                String key = field.getKey();
                if (key.equals("id") || key.equals("deleted_token") || key.endsWith("_id") || key.endsWith("_by"))
                    for (String side : List.of("old", "new")) if (!field.getValue().path(side).isNull()) assertThat(field.getValue().path(side).isString()).as(key).isTrue();
            }
        }
    }
    private List<Map<String, Object>> stored(String trace) {
        return jdbc.query("SELECT id,table_name,row_id,root_table,root_id,action,diff,actor_user_id,request_id,transaction_id FROM sys_data_log WHERE request_id=? ORDER BY id LIMIT 100",
                (rs, n) -> map("id", rs.getString("id"), "table", rs.getString("table_name"), "rowId", rs.getString("row_id"),
                        "rootTable", rs.getString("root_table"), "rootId", rs.getString("root_id"), "action", rs.getString("action"),
                        "diff", json.readTree(rs.getString("diff")), "actorUserId", rs.getString("actor_user_id"),
                        "requestId", rs.getString("request_id"), "transactionId", rs.getString("transaction_id")), trace);
    }
    private List<Map<String, Object>> timeline(String trace) { return jdbc.queryForList("SELECT id::text,doc_type,doc_id::text,action,operator_id::text,request_id FROM biz_doc_log WHERE request_id=? ORDER BY id", trace); }
    private List<Map<String, Object>> operations(String trace) { return jdbc.queryForList("SELECT id::text,module,action,success,trace_id,error_msg FROM sys_oper_log WHERE trace_id=? ORDER BY id", trace); }
    private void evidence(String file, Object value) throws Exception {
        Path path = Path.of("test-artifacts/full-audit/tags/s4", file); Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /** 仅本 IT 导入，生产产物中没有暂停开关、测试请求头处理或替代查询。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class AuditProbeConfig {
        @Bean AuditReadProbe auditReadProbe() { return new AuditReadProbe(); }
        @Bean AuditPauseAspect auditPauseAspect(AuditReadProbe probe, JdbcTemplate jdbc) { return new AuditPauseAspect(probe, jdbc); }
    }
    static class AuditReadProbe {
        final Map<String, Gate> gates = new ConcurrentHashMap<>();
    }
    static class Gate {
        final String key = UUID.randomUUID().toString();
        final String table;
        final long id;
        final CountDownLatch ready = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        volatile Map<String, Object> reader;
        Gate(String table, long id) { this.table = table; this.id = id; }
    }
    @Aspect
    static class AuditPauseAspect {
        private final AuditReadProbe probe;
        private final JdbcTemplate jdbc;
        AuditPauseAspect(AuditReadProbe probe, JdbcTemplate jdbc) { this.probe = probe; this.jdbc = jdbc; }
        @Around(value = "execution(* com.lingmao.scm.module.biz.service.impl.TagAuditTargetGuard.check(..)) && args(table,id,historical)",
                argNames = "point,table,id,historical")
        Object afterRealGuard(ProceedingJoinPoint point, String table, long id, AuditTargetGuard.HistoricalRootGuard historical) throws Throwable {
            Object result = point.proceed();
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) return result;
            String key = request.getRequest().getHeader("X-S4-Audit-Probe");
            Gate gate = key == null ? null : probe.gates.get(key);
            if (gate == null || !gate.table.equals(table) || gate.id != id || !gate.used.compareAndSet(false, true)) return result;
            gate.reader = jdbc.queryForMap("SELECT pg_backend_pid() AS pid,current_setting('transaction_isolation') AS isolation,txid_current()::text AS transaction_id");
            gate.ready.countDown();
            if (!gate.resume.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("audit test barrier timed out");
            return result;
        }
    }
}
