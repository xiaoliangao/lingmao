package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.dto.TagRenameReq;
import com.lingmao.scm.module.biz.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** F02 uses actual TCP requests, PostgreSQL constraints/transactions and Redis. All data is synthetic. */
class S2TagsHttpIT extends A2BusinessFixture {
    @Autowired DeploymentNamespace namespace;
    @Autowired TagService tags;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;

    private final java.util.concurrent.ConcurrentLinkedQueue<Map<String,Object>> observedHttp = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private record Target(String type, long id, Long company) { }

    @Test void twelveRealProvidersAuthorizeTheirOwnObjectsAndGetNeverCreatesRegistry() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF");
        Account libraryOnly = custom(f.company(), "ALL", "biz:tag:list"), outsider = domainActor(f.company(), "SELF");
        List<Target> targets = targets(f, actor);
        List<Object> evidence = new ArrayList<>();
        for (Target target : targets) {
            JsonNode empty = ok(actor, "GET", path(target), null, target.company());
            assertThat(empty.path("version").intValue()).isZero(); assertThat(empty.path("tags").isEmpty()).isTrue();
            assertThat(registries(target)).isZero();
            JsonNode noRead = call(libraryOnly, "GET", path(target), null, target.company());
            JsonNode noWrite = call(libraryOnly, "POST", path(target) + "/replace", replace(0, List.of("not-authorized"), Map.of(), List.of()), target.company());
            assertThat(noRead.path("code").intValue()).as(target.type() + " read permission").isEqualTo(403);
            assertThat(noWrite.path("code").intValue()).as(target.type() + " write permission").isEqualTo(403);
            JsonNode hiddenRead = null, hiddenWrite = null;
            if (target.company() != null) {
                hiddenRead = call(outsider, "GET", path(target), null, target.company());
                hiddenWrite = call(outsider, "POST", path(target) + "/replace", replace(0, List.of("not-visible"), Map.of(), List.of()), target.company());
                assertThat(hiddenRead.path("code").intValue()).as(target.type() + " read DataScope").isEqualTo(404);
                assertThat(hiddenWrite.path("code").intValue()).as(target.type() + " write DataScope").isEqualTo(404);
            }
            assertThat(registries(target)).isZero();
            JsonNode saved = ok(actor, "POST", path(target) + "/replace", replace(0, List.of("#测试-" + suffix()), Map.of(), List.of()), target.company());
            assertThat(saved.path("version").intValue()).isEqualTo(1); assertThat(saved.path("tags").size()).isEqualTo(1);
            assertThat(saved.path("tags").get(0).path("id").isString()).isTrue();
            assertThat(saved.toString()).doesNotContain("tenantKey", "deletedToken", "canonical", "price", "companyId");
            assertThat(registries(target)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE tenant_key=? AND object_type=? AND object_id=?",
                    Long.class, namespace.tenant(), target.type(), target.id())).isEqualTo(target.company());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type=? AND doc_id=? AND action='replace-tags'", Long.class, target.type(), target.id())).isEqualTo(1);
            evidence.add(map("type", target.type(), "objectId", Long.toString(target.id()), "companyId", target.company() == null ? null : target.company().toString(),
                    "read", empty, "saved", saved, "missingReadPermission", noRead, "missingWritePermission", noWrite,
                    "outsideReadScope", hiddenRead, "outsideWriteScope", hiddenWrite));
        }
        // A new tag object version is independent from every business version.
        Target receipt = targets.stream().filter(t -> t.type().equals("PRODUCTION_RECEIPT")).findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT version FROM prd_receipt WHERE id=?", Integer.class, receipt.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM prd_receipt WHERE id=?", String.class, receipt.id())).isEqualTo("APPROVED");
        write("providers-12.json", evidence);
    }

    @Test void ordinaryScopeCompanyAndDomainPermissionsBlockForeignObjectsAndUnrelatedSourceExceptions() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF"), other = domainActor(f.company(), "SELF");
        Target own = new Target("ORDER", sourceOrder(f, owner, "DRAFT", f.company()), f.company());
        Target hidden = new Target("ORDER", sourceOrder(f, other, "DRAFT", f.company()), f.company());
        Target foreign = new Target("ORDER", sourceOrder(f, owner, "DRAFT", f.otherCompany()), f.otherCompany());
        JsonNode hiddenTags = ok(other, "POST", path(hidden) + "/replace", replace(0, List.of("hidden-" + suffix()), Map.of(), List.of()), f.company());
        long hiddenTag = id(hiddenTags.path("tags").get(0).path("id")); String hiddenName = hiddenTags.path("tags").get(0).path("name").stringValue();
        assertThat(call(owner, "GET", path(hidden), null, f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(call(owner, "POST", path(hidden) + "/replace", replace(0, List.of("attack"), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(call(owner, "GET", path(foreign), null, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(call(owner, "GET", path(own), null, null).path("code").intValue()).isEqualTo(400);
        assertThat(ok(owner, "GET", path(foreign), null, f.otherCompany()).path("tags").isEmpty()).isTrue();
        // The library is intentionally shared; seeing a library ID never proves an object association.
        assertThat(ok(owner, "GET", "/api/tags?includeIds=" + hiddenTag + "&size=1", null, f.company()).get(0).path("id").stringValue()).isEqualTo(Long.toString(hiddenTag));
        assertThat(call(owner, "POST", path(own) + "/replace", replace(0, List.of("#" + hiddenName), Map.of(), List.of(hiddenTag)), f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(registries(own)).isZero();
        Account fgOnly = custom(f.company(), "ALL", "fg:doc:add", "fg:doc:edit", "biz:tag:list");
        assertThat(call(fgOnly, "GET", path(own), null, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(call(fgOnly, "POST", path(own) + "/replace", replace(0, List.of("attack"), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(403);
        Account libraryOnly = custom(f.company(), "ALL", "biz:tag:list");
        assertThat(call(libraryOnly, "GET", "/api/tags/objects/STYLE/" + f.style(), null, null).path("code").intValue()).isEqualTo(403);
        assertThat(call(libraryOnly, "POST", "/api/tags/" + hiddenTag + "/archive", Map.of("version", 0), null).path("code").intValue()).isEqualTo(403);
        write("scope-and-company.json", map("sharedOption", ok(owner, "GET", "/api/tags?size=1&includeIds=" + hiddenTag, null, null),
                "hiddenObject", call(owner, "GET", path(hidden), null, f.company()), "wrongCompany", call(owner, "GET", path(foreign), null, f.company())));
    }

    @Test void pendingReviewerReadExceptionNeverGrantsTagWriteOrAllScope() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF"), reviewer = domainActor(f.company(), "SELF");
        long order = sourceOrder(f, owner, "PENDING", f.company());
        long purchase = sourcePurchase(f, owner); jdbc.update("UPDATE pur_order SET status='PENDING' WHERE id=?", purchase);
        long production = sourceProduction(f, owner, order, "PENDING");
        for (Target target : List.of(new Target("ORDER", order, f.company()), new Target("PURCHASE_ORDER", purchase, f.company()), new Target("PRODUCTION_ORDER", production, f.company()))) {
            jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES(?,?,1,1,?,'CURRENT')", target.type(), target.id(), reviewer.id());
            try {
                assertThat(ok(reviewer, "GET", path(target), null, f.company()).path("tags").isEmpty()).isTrue();
                assertThat(call(reviewer, "POST", path(target) + "/replace", replace(0, List.of("reviewer"), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(404);
                jdbc.update("UPDATE biz_approval_step SET status='PASSED' WHERE biz_type=? AND doc_id=?", target.type(), target.id());
                assertThat(call(reviewer, "GET", path(target), null, f.company()).path("code").intValue()).isEqualTo(404);
            } finally { jdbc.update("DELETE FROM biz_approval_step WHERE biz_type=? AND doc_id=?", target.type(), target.id()); }
        }
    }

    @Test void realDomainStatesAndReceiptMetadataRulesAreEnforcedUnderParentLocks() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"); List<Target> values = targets(f, actor);
        Map<String, String> tables = Map.of("ORDER", "ord_order", "PURCHASE_ORDER", "pur_order", "INV_DOC", "inv_doc",
                "INV_COUNT", "inv_count", "PRODUCTION_ORDER", "prd_order", "FG_DOC", "fg_doc");
        for (Target target : values) {
            if (!tables.containsKey(target.type())) continue;
            // No inventory movement is requested: these synthetic states test only the domain tag gate.
            if (target.type().equals("FG_DOC")) {
                jdbc.update("UPDATE fg_doc SET status='VOIDED',version=version+1 WHERE id=?", target.id());
            } else jdbc.update("UPDATE " + tables.get(target.type()) + " SET status='VOIDED' WHERE id=?", target.id());
            assertThat(call(actor, "POST", path(target) + "/replace", replace(0, List.of("state-" + suffix()), Map.of(), List.of()), target.company()).path("code").intValue()).isEqualTo(1002);
            assertThat(registries(target)).isZero();
        }
        jdbc.update("UPDATE sup_supplier SET status='ADMITTING' WHERE id=?", f.supplier());
        assertThat(call(actor, "POST", "/api/tags/objects/SUPPLIER/" + f.supplier() + "/replace", replace(0, List.of("blocked"), Map.of(), List.of()), null).path("code").intValue()).isEqualTo(1000);
        Target pack = values.stream().filter(t -> t.type().equals("TECH_PACK")).findFirst().orElseThrow();
        jdbc.update("UPDATE sty_style SET status='DISABLED' WHERE id=?", f.style());
        assertThat(call(actor, "POST", path(pack) + "/replace", replace(0, List.of("disabled-style"), Map.of(), List.of()), null).path("code").intValue()).isEqualTo(1000);
        jdbc.update("UPDATE sty_style SET status='ENABLED' WHERE id=?", f.style());
        Target mrp = values.stream().filter(t -> t.type().equals("MRP_CALC")).findFirst().orElseThrow();
        jdbc.update("UPDATE mrp_calc SET status='CLOSED',close_reason='MANUAL' WHERE id=?", mrp.id());
        assertThat(call(actor, "POST", path(mrp) + "/replace", replace(0, List.of("closed"), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(1002);
        Target receipt = values.stream().filter(t -> t.type().equals("PRODUCTION_RECEIPT")).findFirst().orElseThrow();
        long parent = jdbc.queryForObject("SELECT prd_order_id FROM prd_receipt WHERE id=?", Long.class, receipt.id());
        jdbc.update("UPDATE prd_order SET status='CLOSED' WHERE id=?", parent);
        assertThat(call(actor, "POST", path(receipt) + "/replace", replace(0, List.of("closed-parent"), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(1002);
        jdbc.update("UPDATE prd_order SET status='DONE' WHERE id=?", parent);
        assertThat(ok(actor, "POST", path(receipt) + "/replace", replace(0, List.of("valid-done"), Map.of(), List.of()), f.company()).path("version").intValue()).isEqualTo(1);
        jdbc.update("UPDATE prd_receipt SET status='VOIDED' WHERE id=?", receipt.id());
        assertThat(call(actor, "POST", path(receipt) + "/replace", replace(1, List.of(), Map.of(), List.of()), f.company()).path("code").intValue()).isEqualTo(1002);
    }

    @Test void twentyHttpWritersMergeOneNameAndSameKeyOnlyReplaysOneObjectMutation() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"); String name = "Merge-" + suffix();
        List<Long> objects = new ArrayList<>();
        for (int n = 0; n < 20; n++) objects.add(styleCopy(f));
        List<JsonNode> creates = parallel(20, n -> request(actor, "POST", "/api/tags/objects/STYLE/" + objects.get(n) + "/replace",
                replace(0, List.of(n % 2 == 0 ? "#" + name : "＃" + name.toUpperCase(java.util.Locale.ROOT)), Map.of(), List.of()), null, UUID.randomUUID().toString(), null));
        assertThat(creates).allSatisfy(reply -> assertThat(reply.path("code").intValue()).as(reply.toString()).isZero());
        List<Long> ids = creates.stream().map(reply -> id(reply.path("data").path("tags").get(0).path("id"))).distinct().toList();
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=? AND deleted_token=0", Long.class, namespace.tenant(), name.toLowerCase(java.util.Locale.ROOT))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_link WHERE tag_id=?", Long.class, ids.getFirst())).isEqualTo(20);
        long same = styleCopy(f); String key = UUID.randomUUID().toString(); Object body = replace(0, List.of("same-key-" + suffix()), Map.of(), List.of());
        String path = "/api/tags/objects/STYLE/" + same + "/replace";
        List<JsonNode> attempts = parallel(20, n -> request(actor, "POST", path, body, null, key, null));
        assertThat(attempts).allSatisfy(reply -> assertThat(reply.path("code").intValue()).isIn(0, 409));
        JsonNode replay = request(actor, "POST", path, body, null, key, null); assertThat(replay.path("code").intValue()).isZero();
        assertThat(replay.path("data").path("version").intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='STYLE' AND doc_id=? AND action='replace-tags'", Long.class, same)).isEqualTo(1);
        assertThat(request(actor, "POST", path, replace(1, List.of(), Map.of(), List.of()), null, key, null).path("code").intValue()).isEqualTo(1002);
        List<JsonNode> cas = parallel(2, n -> request(actor, "POST", "/api/tags/objects/MATERIAL/" + f.material() + "/replace",
                replace(0, List.of("cas-" + n + "-" + name), Map.of(), List.of()), null, UUID.randomUUID().toString(), null));
        assertThat(cas.stream().map(r -> r.path("code").intValue()).sorted().toList()).containsExactly(0, 1002);
        write("http-concurrency-20.json", map("mergeResponses", creates, "sameKeyResponses", attempts, "sameKeyReplay", replay, "casResponses", cas));
    }

    @Test void archiveRecreateStaleRenameAndTwoSameNameHistoricalIdsRemainExplicit() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"), manager = manager(f.company());
        Target target = new Target("STYLE", f.style(), null); String oldName = "Archive-" + suffix(), secondName = "Other-" + suffix();
        JsonNode first = ok(actor, "POST", path(target) + "/replace", replace(0, List.of("#" + oldName, "#" + secondName), Map.of(), List.of()), null);
        long archivedId = id(first.path("tags").get(0).path("id")), secondId = id(first.path("tags").get(1).path("id"));
        JsonNode archived = ok(manager, "POST", "/api/tags/" + archivedId + "/archive", Map.of("version", 0), null);
        JsonNode renamed = ok(manager, "POST", "/api/tags/" + secondId + "/rename", Map.of("version", 0, "name", "#" + oldName), null);
        assertThat(archived.path("archived").booleanValue()).isTrue();
        assertThat(call(actor, "POST", path(target) + "/replace", replace(1, List.of("#" + oldName, "#" + secondName), versions(first), List.of(archivedId, secondId)), null).path("code").intValue()).isEqualTo(1002);
        JsonNode refreshed = ok(actor, "GET", path(target), null, null);
        assertThat(refreshed.path("tags").size()).isEqualTo(2);
        assertThat(refreshed.path("tags").get(0).path("name").stringValue()).isEqualTo(oldName);
        assertThat(refreshed.path("tags").get(1).path("name").stringValue()).isEqualTo(oldName);
        JsonNode kept = ok(actor, "POST", path(target) + "/replace", replace(1, List.of("#" + oldName), versions(refreshed), List.of(secondId, archivedId)), null);
        assertThat(id(kept.path("tags").get(0).path("id"))).isEqualTo(secondId);
        assertThat(id(kept.path("tags").get(1).path("id"))).isEqualTo(archivedId);
        JsonNode logs = ok(actor, "GET", "/api/tags/" + archivedId + "/logs", null, null);
        assertThat(logs.path("total").intValue()).isEqualTo(1); assertThat(logs.path("list").get(0).path("action").stringValue()).isEqualTo("archive");
        ok(manager, "POST", "/api/tags/" + secondId + "/archive", Map.of("version", 1), null);
        JsonNode latest = ok(actor, "GET", path(target), null, null);
        JsonNode empty = ok(actor, "POST", path(target) + "/replace", replace(2, List.of(), versions(latest), List.of()), null);
        JsonNode recreated = ok(actor, "POST", path(target) + "/replace", replace(3, List.of("#" + oldName), Map.of(), List.of()), null);
        assertThat(id(recreated.path("tags").get(0).path("id"))).isNotIn(archivedId, secondId);
        assertThat(empty.path("tags").isEmpty()).isTrue();
        assertThat(call(manager, "POST", "/api/tags/" + archivedId + "/rename", Map.of("version", 1, "name", "forbidden"), null).path("code").intValue()).isEqualTo(1002);
        write("archive-rename-retain.json", map("first", first, "archived", archived, "renamed", renamed, "refreshed", refreshed, "kept", kept, "recreated", recreated, "logs", logs));
    }

    @Test void optionsEnforceOneTotalBudgetLiteralPrefixesLongIdsAndDtoWhitelists() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"); String prefix = "opt-" + suffix();
        List<Long> ids = jdbc.queryForList("INSERT INTO biz_tag(tenant_key,name,canonical) SELECT ?,?||n,?||n FROM generate_series(1,110) n RETURNING id", Long.class, namespace.tenant(), prefix, prefix);
        List<Long> include = new ArrayList<>(ids.subList(0, 100)); java.util.Collections.reverse(include);
        JsonNode full = ok(actor, "GET", "/api/tags?keyword=" + prefix + "&size=50&includeIds=" + join(include), null, null);
        assertThat(full.size()).isEqualTo(100); for (int n = 0; n < 100; n++) assertThat(id(full.get(n).path("id"))).isEqualTo(include.get(n));
        JsonNode dedup = ok(actor, "GET", "/api/tags?keyword=" + prefix + "&size=3&includeIds=" + ids.get(0) + "," + ids.get(0) + "," + ids.get(1), null, null);
        assertThat(dedup.size()).isEqualTo(3); assertThat(id(dedup.get(0).path("id"))).isEqualTo(ids.get(0));
        assertThat(call(actor, "GET", "/api/tags?includeIds=" + join(ids.subList(0, 101)), null, null).path("code").intValue()).isEqualTo(400);
        assertThat(call(actor, "GET", "/api/tags?size=101", null, null).path("code").intValue()).isEqualTo(400);
        assertThat(call(actor, "GET", "/api/tags?includeIds=" + ids.getFirst() + ",9223372036854775807", null, null).path("code").intValue()).isEqualTo(404);
        String literal = prefix + "%_\\tail";
        long literalId = insertTag(literal); insertTag(prefix + "x_tail"); insertTag(prefix + "%X\\tail");
        JsonNode matching = ok(actor, "GET", "/api/tags?keyword=" + encoded(prefix + "%_\\") + "&size=100", null, null);
        assertThat(matching.size()).isEqualTo(1); assertThat(id(matching.get(0).path("id"))).isEqualTo(literalId);
        long large = 9007199254740993L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1000000L);
        jdbc.update("INSERT INTO biz_tag(id,tenant_key,name,canonical) OVERRIDING SYSTEM VALUE VALUES(?,?,?,?)", large, namespace.tenant(), prefix + "long", prefix + "long");
        JsonNode largeId = ok(actor, "GET", "/api/tags?size=1&includeIds=" + large, null, null);
        assertThat(largeId.get(0).path("id").isString()).isTrue(); assertThat(largeId.get(0).path("id").stringValue()).isEqualTo(Long.toString(large));
        Map<String,Object> malicious = new LinkedHashMap<>(replace(0, List.of("##蓝"), Map.of(), List.of()));
        malicious.put("tenantKey", "foreign"); malicious.put("status", "APPROVED"); malicious.put("companyId", f.otherCompany()); malicious.put("auditBy", actor.id());
        JsonNode rejected = call(actor, "POST", "/api/tags/objects/STYLE/" + f.style() + "/replace", malicious, f.company());
        assertThat(rejected.path("code").intValue()).isEqualTo(400);
        assertThat(registries(new Target("STYLE", f.style(), null))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sty_style WHERE id=?", String.class, f.style())).isEqualTo("ENABLED");
        JsonNode saved = ok(actor, "POST", "/api/tags/objects/STYLE/" + f.style() + "/replace", replace(0, List.of("##蓝"), Map.of(), List.of()), f.company());
        assertThat(saved.path("tags").get(0).path("name").stringValue()).isEqualTo("#蓝");
        long original = id(saved.path("tags").get(0).path("id"));
        JsonNode roundTrip = ok(actor, "POST", "/api/tags/objects/STYLE/" + f.style() + "/replace", replace(1, List.of("##蓝"), versions(saved), List.of(original)), null);
        assertThat(roundTrip.path("tags")).isEqualTo(saved.path("tags"));
        assertThat(jdbc.queryForObject("SELECT status FROM sty_style WHERE id=?", String.class, f.style())).isEqualTo("ENABLED");
        assertThat(jdbc.queryForObject("SELECT tenant_key FROM biz_tag WHERE id=?", String.class, original)).isEqualTo(namespace.tenant());
        assertThat(call(actor, "GET", "/api/tags/objects/PUBLIC_TAG/1", null, null).path("code").intValue()).isEqualTo(400);
        assertThat(call(actor, "POST", "/api/tags/objects/MATERIAL/" + f.material() + "/replace", Map.of("version", 0, "names", List.of()), null).path("code").intValue()).isEqualTo(400);
        assertThat(node(raw(actor, "POST", "/api/tags/objects/MATERIAL/" + f.material() + "/replace", replace(0, List.of(), Map.of(), List.of()), null, null)).path("code").intValue()).isEqualTo(400);
        write("options-bounds-long-id.json", map("include100", full, "dedup", dedup, "literal", matching, "longId", largeId, "dtoRejected", rejected, "dtoResult", saved, "roundTrip", roundTrip));
    }

    @Test void httpNamesUseUnicodeCodePointsAndRejectTheNextCodePointWithoutPartialMutation() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF");
        String allowed = "😀".repeat(64), rejectedName = allowed + "😀";
        String path = "/api/tags/objects/STYLE/" + f.style();
        JsonNode saved = ok(actor, "POST", path + "/replace", replace(0, List.of(allowed), Map.of(), List.of()), null);
        assertThat(saved.path("tags").get(0).path("name").stringValue()).isEqualTo(allowed);
        long tag = id(saved.path("tags").get(0).path("id"));
        assertThat(jdbc.queryForObject("SELECT char_length(name) FROM biz_tag WHERE id=?", Integer.class, tag)).isEqualTo(64);
        JsonNode rejected = call(actor, "POST", path + "/replace", replace(1, List.of(rejectedName), versions(saved), List.of()), null);
        assertThat(rejected.path("code").intValue()).isEqualTo(400);
        JsonNode unchanged = ok(actor, "GET", path, null, null);
        assertThat(unchanged).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='STYLE' AND doc_id=? AND action='replace-tags'", Long.class, f.style())).isEqualTo(1);
        write("unicode-http-boundary.json", map("acceptedCodePoints", 64, "acceptedUtf16Units", allowed.length(),
                "saved", saved, "rejectedCodePoints", 65, "rejectedResponse", rejected, "afterRejection", unchanged));
    }

    @Test void completedReplayRechecksPermissionsAndOwnerAfterTheOriginalCommit() throws Exception {
        Fixture f = fixture(); Account owner = domainActor(f.company(), "SELF"), other = domainActor(f.company(), "SELF");
        long order = sourceOrder(f, owner, "DRAFT", f.company()); String path = "/api/tags/objects/ORDER/" + order + "/replace", key = UUID.randomUUID().toString();
        Object body = replace(0, List.of("replay-" + suffix()), Map.of(), List.of());
        assertThat(request(owner, "POST", path, body, f.company(), key, null).path("code").intValue()).isZero();
        jdbc.update("UPDATE ord_order SET create_by=?,sales_id=?,merch_id=? WHERE id=?", other.id(), other.id(), other.id(), order);
        assertThat(request(owner, "POST", path, body, f.company(), key, null).path("code").intValue()).isEqualTo(404);
        jdbc.update("UPDATE ord_order SET create_by=?,sales_id=?,merch_id=? WHERE id=?", owner.id(), owner.id(), owner.id(), order);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='order:order:edit')", owner.role());
        assertThat(request(owner, "POST", path, body, f.company(), key, null).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='ORDER' AND doc_id=? AND action='replace-tags'", Long.class, order)).isEqualTo(1);
    }

    @Test void renameCommitInvalidatesEveryProjectionButRollbackLeavesCacheAndDatabaseUntouched() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"), manager = manager(f.company());
        String original = "cache-" + suffix(), changed = original + "-new";
        JsonNode saved = ok(actor, "POST", "/api/tags/objects/STYLE/" + f.style() + "/replace", replace(0, List.of(original), Map.of(), List.of()), null);
        long tag = id(saved.path("tags").get(0).path("id"));
        assertThat(ok(actor, "GET", "/api/tags?keyword=" + original, null, null).get(0).path("name").stringValue()).isEqualTo(original);
        ok(actor, "GET", "/api/tags/objects/STYLE/" + f.style(), null, null);
        String epochKey = namespace.sharedKey("public-tags-v1", "library-epoch"), beforeEpoch = redis.opsForValue().get(epochKey);
        assertThat(beforeEpoch).isNotNull();
        long outboxBefore = jdbc.queryForObject("SELECT count(*) FROM sys_outbox", Long.class);
        authenticate(manager);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tags.rename(tag, new TagRenameReq(0, changed));
            assertThat(redis.opsForValue().get(epochKey)).isEqualTo(beforeEpoch);
            assertThat(jdbc.queryForObject("SELECT name FROM biz_tag WHERE id=?", String.class, tag)).isEqualTo(changed);
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(epochKey)).isEqualTo(beforeEpoch);
        assertThat(jdbc.queryForObject("SELECT name FROM biz_tag WHERE id=?", String.class, tag)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox", Long.class)).isEqualTo(outboxBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='PUBLIC_TAG' AND doc_id=?", Long.class, tag)).isZero();
        ok(manager, "POST", "/api/tags/" + tag + "/rename", Map.of("version", 0, "name", changed), null);
        JsonNode current = ok(actor, "GET", "/api/tags/objects/STYLE/" + f.style(), null, null);
        assertThat(current.path("tags").get(0).path("name").stringValue()).isEqualTo(changed);
        assertThat(redis.opsForValue().get(epochKey)).isNotEqualTo(beforeEpoch);
        assertThat(ok(actor, "GET", "/api/tags?keyword=" + changed, null, null).get(0).path("name").stringValue()).isEqualTo(changed);
        write("cache-transaction.json", map("before", saved, "afterCommit", current, "rolledBackOutboxRows", 0,
                "databaseName", jdbc.queryForObject("SELECT name FROM biz_tag WHERE id=?", String.class, tag)));
    }

    @Test void realAuditRecordsStringIdsDiffAndTraceAndAFailedTimelineRollsBackAllWrites() throws Exception {
        Fixture f = fixture(); Account actor = domainActor(f.company(), "SELF"), manager = manager(f.company());
        String firstName = "audit-" + suffix(), afterName = firstName + "-renamed", trace = "s2-tags-" + suffix();
        JsonNode created = ok(actor, "POST", "/api/tags/objects/STYLE/" + f.style() + "/replace", replace(0, List.of(firstName), Map.of(), List.of()), null);
        long tag = id(created.path("tags").get(0).path("id"));
        JsonNode response = request(manager, "POST", "/api/tags/" + tag + "/rename", Map.of("version", 0, "name", afterName), null, UUID.randomUUID().toString(), trace);
        assertThat(response.path("code").intValue()).isZero();
        var audit = jdbc.queryForMap("SELECT diff::text AS diff,actor_user_id,request_id,root_table,root_id FROM sys_data_log WHERE table_name='biz_tag' AND row_id=? AND action='UPDATE'", tag);
        JsonNode diff = json.readTree(audit.get("diff").toString());
        assertThat(diff.path("name").path("old").stringValue()).isEqualTo(firstName); assertThat(diff.path("name").path("new").stringValue()).isEqualTo(afterName);
        assertThat(audit.get("actor_user_id")).isEqualTo(manager.id()); assertThat(audit.get("request_id")).isEqualTo(trace);
        assertThat(audit.get("root_table")).isEqualTo("biz_tag"); assertThat(audit.get("root_id")).isEqualTo(tag);
        String inserted = jdbc.queryForObject("SELECT diff::text FROM sys_data_log WHERE table_name='biz_tag' AND row_id=? AND action='INSERT'", String.class, tag);
        assertThat(json.readTree(inserted).path("id").path("new").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT request_id FROM biz_doc_log WHERE doc_type='PUBLIC_TAG' AND doc_id=? AND action='rename'", String.class, tag)).isEqualTo(trace);
        String rollbackName = "rollback-" + suffix(); String function = "s2_tag_fail_" + suffix();
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.doc_type='MATERIAL' AND NEW.doc_id=" + f.material()
                + " AND NEW.action='replace-tags' THEN RAISE EXCEPTION 'synthetic internal detail must remain private'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        JsonNode failed;
        try {
            failed = request(actor, "POST", "/api/tags/objects/MATERIAL/" + f.material() + "/replace", replace(0, List.of(rollbackName), Map.of(), List.of()), null, UUID.randomUUID().toString(), "s2-tag-rollback-" + suffix());
            assertThat(failed.path("code").intValue()).isEqualTo(500);
            assertThat(failed.toString()).doesNotContain("synthetic internal", "biz_doc_log", "SQLException", "stack");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=?", Long.class, namespace.tenant(), rollbackName)).isZero();
            assertThat(registries(new Target("MATERIAL", f.material(), null))).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name IN ('biz_tag_object','biz_tag_link') AND root_table='bas_material' AND root_id=?", Long.class, f.material())).isZero();
        } finally { jdbc.execute("DROP TRIGGER " + function + " ON biz_doc_log"); jdbc.execute("DROP FUNCTION " + function + "()"); }
        write("data-log-and-rollback.json", map("renameResponse", response, "diff", diff, "idDiff", json.readTree(inserted), "audit", audit, "failedResponse", failed));
    }

    private Account domainActor(long company, String scope) throws Exception {
        return custom(company, scope, "biz:tag:list", "style:style:list", "style:style:edit", "basic:material:list", "basic:material:edit",
                "supplier:supplier:list", "supplier:supplier:edit", "techpack:pack:list", "techpack:pack:edit", "order:order:list", "order:order:edit",
                "purchase:mrp:list", "purchase:mrp:edit", "purchase:order:list", "purchase:order:edit", "inventory:doc:list", "inventory:doc:edit",
                "inventory:count:list", "inventory:count:edit", "production:order:list", "production:order:edit", "production:receipt:add", "fg:doc:list", "fg:doc:edit");
    }
    private Account manager(long company) throws Exception { return custom(company, "ALL", "biz:tag:list", "biz:tag:manage"); }
    private List<Target> targets(Fixture f, Account actor) {
        long order = sourceOrder(f, actor, "DRAFT", f.company());
        long source = sourceOrder(f, actor, "APPROVED", f.company());
        long purchase = sourcePurchase(f, actor); jdbc.update("UPDATE pur_order SET status='DRAFT' WHERE id=?", purchase);
        long production = sourceProduction(f, actor, source, "DRAFT"); long receiptParent = sourceProduction(f, actor, source, "APPROVED");
        long receipt = jdbc.queryForObject("INSERT INTO prd_receipt(no,company_id,prd_order_id,receipt_date,status,create_by) VALUES(?,?,?,CURRENT_DATE,'APPROVED',?) RETURNING id", Long.class, "TGHH" + suffix(), f.company(), receiptParent, actor.id());
        long mrp = jdbc.queryForObject("INSERT INTO mrp_calc(no,company_id,status,create_by) VALUES(?,?,'CONFIRMED',?) RETURNING id", Long.class, "TGMRP" + suffix(), f.company(), actor.id());
        jdbc.update("INSERT INTO mrp_calc_order(calc_id,order_id) VALUES(?,?)", mrp, source);
        long inv = jdbc.queryForObject("INSERT INTO inv_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, "TGINV" + suffix(), f.company(), f.warehouse(), actor.id());
        long count = jdbc.queryForObject("INSERT INTO inv_count(no,company_id,warehouse_id,count_date,counter_id,status,create_by) VALUES(?,?,?,CURRENT_DATE,?,'DRAFT',?) RETURNING id", Long.class, "TGCNT" + suffix(), f.company(), f.warehouse(), actor.id(), actor.id());
        long pack = jdbc.queryForObject("INSERT INTO tp_tech_pack(style_id,stage,version,status) VALUES(?,'PROD',2,'DRAFT') RETURNING id", Long.class, f.style());
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,'Tag finished goods','PRODUCT') RETURNING id", Long.class, "TGW" + suffix());
        long fg = jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, "TGFG" + suffix(), f.company(), warehouse, actor.id());
        return List.of(new Target("STYLE", f.style(), null), new Target("MATERIAL", f.material(), null), new Target("SUPPLIER", f.supplier(), null),
                new Target("TECH_PACK", pack, null), new Target("ORDER", order, f.company()), new Target("MRP_CALC", mrp, f.company()),
                new Target("PURCHASE_ORDER", purchase, f.company()), new Target("INV_DOC", inv, f.company()), new Target("INV_COUNT", count, f.company()),
                new Target("PRODUCTION_ORDER", production, f.company()), new Target("PRODUCTION_RECEIPT", receipt, f.company()), new Target("FG_DOC", fg, f.company()));
    }
    private long styleCopy(Fixture f) {
        return jdbc.queryForObject("INSERT INTO sty_style(code,name,style_class,brand,year,season,source,category_id,size_group_id,status) VALUES(?,'Tag copy','FS','TG',2026,'AUTUMN','OWN',?,?,'DRAFT') RETURNING id", Long.class, "TGST" + suffix(), f.category(), f.sizeGroup());
    }
    private long insertTag(String name) { return jdbc.queryForObject("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES(?,?,?) RETURNING id", Long.class, namespace.tenant(), name, name.toLowerCase(java.util.Locale.ROOT)); }
    private long registries(Target target) { return jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE tenant_key=? AND object_type=? AND object_id=?", Long.class, namespace.tenant(), target.type(), target.id()); }
    private static String path(Target target) { return "/api/tags/objects/" + target.type() + "/" + target.id(); }
    private static String join(List<Long> ids) { return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")); }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String,Object> replace(int version, List<String> names, Map<String,Integer> versions, List<Long> retain) {
        return map("version", version, "names", names, "tagVersions", versions, "retainIds", retain.stream().map(String::valueOf).toList());
    }
    private static Map<String,Integer> versions(JsonNode object) { Map<String,Integer> versions = new LinkedHashMap<>(); object.path("tags").forEach(tag -> versions.put(tag.path("id").stringValue(), tag.path("version").intValue())); return versions; }
    private JsonNode request(Account actor, String method, String path, Object body, Long company, String key, String trace) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + actor.token());
            if (company != null) request.header("X-Company-Id", company.toString());
            if (key != null) request.header("Idempotency-Key", key);
            if (trace != null) request.header("X-Request-Id", trace);
            HttpResponse<byte[]> response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (trace != null) assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
            JsonNode node = node(response);
            observedHttp.add(map("method", method, "path", path, "httpStatus", response.statusCode(), "code", node.path("code").intValue(),
                    "requestId", response.headers().firstValue("X-Request-Id").orElse(null)));
            if (node.path("code").intValue() == 409) assertThat(response.statusCode()).isEqualTo(409);
            return node;
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static <T> List<T> parallel(int count, IntFunction<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, count).mapToObj(i -> pool.submit(() -> { ready.countDown(); start.await(); return work.apply(i); })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<T> results = new ArrayList<>(); for (var task : tasks) results.add(task.get(30, TimeUnit.SECONDS)); return results;
        }
    }
    private void write(String name, Object value) throws Exception { Path dir = Path.of("target/a2-tags"); Files.createDirectories(dir); Files.writeString(dir.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value)); if (!observedHttp.isEmpty()) Files.writeString(dir.resolve(name.replace(".json", "-http.json")), json.writerWithDefaultPrettyPrinter().writeValueAsString(observedHttp)); }
}
