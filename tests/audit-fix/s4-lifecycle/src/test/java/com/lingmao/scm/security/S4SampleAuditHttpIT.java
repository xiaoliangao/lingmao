package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.dev.service.impl.SampleAuditTargetGuard;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/** 主线均用实际 HTTP；只读 SQL 保存实际事实，故障 SQL 只在独立测试库内安装。 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"lingmao.observability.slow-sql-ms=20", "lingmao.proxy.trusted-cidrs="})
@Import(S4SampleAuditHttpIT.ProbeConfiguration.class)
class S4SampleAuditHttpIT extends S2SampleFixture {
    @Autowired DeploymentNamespace namespace;
    @Autowired SampleAuditTargetGuard guard;
    @Autowired SampleProbe probe;
    private static final Set<String> TABLES = Set.of("dev_sample", "dev_sample_color", "dev_sample_order",
            "dev_sample_order_color", "dev_sample_order_size", "dev_sample_node", "dev_sample_review", "dev_sample_reviewer");
    private static final Map<String, Set<String>> COMPOUND_KEYS = Map.of(
            "dev_sample_color", Set.of("tenant_key", "company_id", "sample_id", "color_id"),
            "dev_sample_order_color", Set.of("tenant_key", "company_id", "sample_order_id", "color_id"),
            "dev_sample_order_size", Set.of("tenant_key", "company_id", "sample_order_id", "size_code"),
            "dev_sample_reviewer", Set.of("tenant_key", "company_id", "review_id", "user_id"));

    @AfterEach void archiveActualHttp(TestInfo test) throws Exception {
        List<Map<String, Object>> actual;
        synchronized (sampleHttpEvidence) { actual = new ArrayList<>(sampleHttpEvidence); }
        evidence(test.getTestMethod().orElseThrow().getName() + ".http.json", actual);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void actualEightTableFlowKeepsTrueKeysPhysicalCostProjectionAndOneTraceAcrossBothAudits() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f);
        Account cost = auditReader(f.company(), "techpack:cost:query"), orderOnly = auditReader(f.company(), "order:price:query");
        Map<String, Object> body = sampleBody(f, maker);
        body.put("fabricDesc", "S4 private fabric detail"); body.put("remark", "S4 private sample remark");
        String createTrace = trace("create"), updateTrace = trace("update");
        JsonNode made = success(traced(maker, "POST", "/api/samples", body, f.company(), key(), createTrace));
        long sample = id(made.path("id"));
        Map<String, Object> update = updateBody(body, made.path("version").intValue());
        update.put("estCost", "22.4567"); update.put("estTagPrice", "199.90");
        success(traced(maker, "PUT", samplePath(sample, f.company()), update, f.company(), key(), updateTrace));
        dispatchInternal(maker, f.company(), sample, maker.id()); completeSend(maker, f.company(), sample);
        reviewSample(maker, f.company(), sample, "PASS");

        JsonNode permitted = history(cost, "dev_sample", sample, true, f.company());
        JsonNode stripped = history(orderOnly, "dev_sample", sample, true, f.company());
        assertThat(permitted.valueStream().map(row -> row.path("table").asText()).collect(java.util.stream.Collectors.toSet())).isEqualTo(TABLES);
        assertIdsAndKeys(permitted, sample);
        JsonNode costChange = byTrace(permitted, updateTrace, "dev_sample").path("diff");
        assertThat(costChange.path("est_cost").path("old").decimalValue()).isEqualByComparingTo("12.3456");
        assertThat(costChange.path("est_cost").path("new").decimalValue()).isEqualByComparingTo("22.4567");
        assertThat(stripped.toString()).doesNotContain("\"est_cost\"", "12.3456", "22.4567");
        JsonNode publicPrice = byTrace(stripped, updateTrace, "dev_sample").path("diff").path("est_tag_price");
        assertThat(publicPrice.path("old").decimalValue()).isEqualByComparingTo("129.90");
        assertThat(publicPrice.path("new").decimalValue()).isEqualByComparingTo("199.90");

        List<Map<String, Object>> raw = storedRoot(sample);
        assertThat(json.writeValueAsString(raw)).doesNotContain("fabric_desc", "customer_style_no", "remark", "opinion", "recorded_txid", "S4 private");
        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> exchange : List.copyOf(sampleHttpEvidence)) {
            if (exchange.get("method").equals("GET")) continue;
            String request = (String) exchange.get("requestId");
            List<Map<String, Object>> fields = storedTrace(request), timeline = timeline(request), operations = operations(request);
            assertThat(fields).isNotEmpty(); assertThat(timeline).hasSize(1); assertThat(operations).hasSize(1);
            assertThat(fields.stream().map(row -> row.get("transactionId")).distinct().count()).isOne();
            assertThat(fields).allSatisfy(row -> {
                assertThat(row.get("actorUserId")).isEqualTo(Long.toString(maker.id()));
                assertThat(row.get("requestId")).isEqualTo(request);
            });
            assertThat(timeline.getFirst().get("operator_id")).isEqualTo(Long.toString(maker.id()));
            assertThat(timeline.getFirst().get("request_id")).isEqualTo(request);
            assertThat(operations.getFirst().get("success")).isEqualTo(true);
            assertThat(operations.getFirst().get("trace_id")).isEqualTo(request);
            chains.add(map("requestId", request, "fieldAudit", fields, "businessAudit", timeline, "operationAudit", operations));
        }
        evidence("eight-table-http-cost-and-trace.json", map("sampleId", Long.toString(sample), "costPermission", permitted,
                "orderOnlyPermission", stripped, "actualStoredFieldAudit", raw, "actualTraceChains", chains));
    }

    @Test void everyScalarAndTagTargetRechecksCompanyDomainAndGlobalAuditPermissionBeforeEvenAnEmptyPage() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f), allowed = auditReader(f.company());
        Account noDomain = custom(f.company(), "ALL", "system:audit:query");
        Account self = custom(f.company(), "SELF", "system:audit:query", "dev:sample:list");
        long sample = id(createSample(maker, sampleBody(f, maker)).path("id"));
        attachTag(maker, "SAMPLE", sample, f.company());
        long object = registry("SAMPLE", sample), link = firstLink(object);
        dispatchInternal(maker, f.company(), sample, maker.id()); completeSend(maker, f.company(), sample);
        reviewSample(maker, f.company(), sample, "PASS");
        List<Target> targets = new ArrayList<>(scalarTargets(sample));
        targets.add(new Target("dev_sample", sample, true));
        targets.add(new Target("biz_tag_object", object, false)); targets.add(new Target("biz_tag_link", link, false));
        long registryBefore = jdbc.queryForObject("SELECT count(*) FROM biz_tag_object", Long.class);
        var outcomes = new ArrayList<Map<String, Object>>();
        for (Target target : targets) {
            String path = auditPath(target.table(), target.id(), target.root());
            JsonNode actual = sampleOk(allowed, "GET", path, null, f.company());
            assertThat(actual.isArray()).isTrue(); assertThat(actual.size()).isPositive();
            JsonNode denied = sampleCall(noDomain, "GET", path + "&beforeId=1", null, f.company(), null);
            JsonNode missingCompany = sampleCall(allowed, "GET", path + "&beforeId=1", null, null, null);
            JsonNode otherCompany = sampleCall(allowed, "GET", path + "&beforeId=1", null, f.otherCompany(), null);
            JsonNode selfHttp = sampleCall(self, "GET", path, null, f.company(), null);
            assertThat(denied.path("code").intValue()).isEqualTo(403);
            assertThat(missingCompany.path("code").intValue()).isEqualTo(400);
            // 标签按请求主体找真实父头，直接样衣守卫按真实主体与请求头比较。
            assertThat(otherCompany.path("code").intValue()).isEqualTo(target.table().startsWith("biz_tag_") ? 404 : 403);
            assertThat(selfHttp.path("code").intValue()).isEqualTo(403);
            outcomes.add(map("table", target.table(), "id", Long.toString(target.id()), "rootQuery", target.root(),
                    "allowed", actual, "noDomainEvenEmptyPage", denied, "missingCompany", missingCompany,
                    "wrongCompany", otherCompany, "publicSelfStillDenied", selfHttp));
        }
        for (String table : COMPOUND_KEYS.keySet()) for (boolean root : List.of(false, true)) {
            assertThat(sampleCall(allowed, "GET", auditPath(table, sample, root), null, f.company(), null)
                    .path("code").intValue()).as("compound keys have no fabricated scalar API: %s", table).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object", Long.class)).isEqualTo(registryBefore);
        evidence("all-target-permission-and-company.json", map("cases", outcomes, "registryRowsBefore", registryBefore,
                "registryRowsAfter", jdbc.queryForObject("SELECT count(*) FROM biz_tag_object", Long.class)));
    }

    @Test void currentMakerReassignmentRevokesOldVersionGuardsWithoutOpeningPublicSelfAudit() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f);
        Account oldMaker = custom(f.company(), "SELF", "dev:sample:list", "system:audit:query");
        Account newMaker = custom(f.company(), "SELF", "dev:sample:list", "system:audit:query");
        Map<String, Object> body = sampleBody(f, maker); body.put("patternMakerId", oldMaker.id());
        long sample = id(createSample(maker, body).path("id"));
        dispatchInternal(maker, f.company(), sample, oldMaker.id()); completeSend(maker, f.company(), sample);
        reviewSample(maker, f.company(), sample, "REVISE");
        List<Target> oldVersionTargets = scalarTargets(sample);
        authenticate(oldMaker);
        for (Target target : oldVersionTargets) withCompany(f.company(), () -> guard.check(target.table(), target.id()));
        JsonNode oldVisibleBefore = sampleDetail(oldMaker, f.company(), sample);
        assertThat(sampleCall(oldMaker, "GET", auditPath("dev_sample", sample, true), null, f.company(), null).path("code").intValue()).isEqualTo(403);

        JsonNode detail = sampleDetail(maker, f.company(), sample), order = detail.path("currentOrder");
        List<Long> colors = order.path("colors").valueStream().map(color -> id(color.path("id"))).toList();
        List<String> sizes = order.path("sizeCodes").valueStream().map(JsonNode::asText).toList();
        Map<String, Object> change = map("version", order.path("version").intValue(), "sampleVersionLock", detail.path("version").intValue(),
                "sampleType", order.path("sampleType").asText(), "qty", order.path("qty").intValue(), "colorIds", colors, "sizeCodes", sizes,
                "needDate", order.path("needDate").asText(), "reason", order.path("reason").asText(), "requirement", null,
                "makeWay", "INTERNAL", "factoryId", null, "makerId", newMaker.id());
        sampleOk(maker, "PUT", "/api/sample-orders/" + id(order.path("id")) + "?companyId=" + f.company(), change, f.company());
        JsonNode oldAfter = sampleCall(oldMaker, "GET", samplePath(sample, f.company()), null, f.company(), null);
        JsonNode newAfter = sampleDetail(newMaker, f.company(), sample);
        assertThat(oldAfter.path("code").intValue()).isEqualTo(404);
        assertThat(id(newAfter.path("currentOrder").path("makerId"))).isEqualTo(newMaker.id());
        List<Map<String, Object>> actualGuards = new ArrayList<>();
        authenticate(oldMaker);
        for (Target target : oldVersionTargets) {
            Throwable denied = catchThrowable(() -> withCompany(f.company(), () -> guard.check(target.table(), target.id())));
            assertThat(denied).isInstanceOfSatisfying(BizException.class, actual -> assertThat(actual.getCode()).isEqualTo(404));
            actualGuards.add(map("table", target.table(), "id", Long.toString(target.id()), "oldMakerCode", ((BizException) denied).getCode()));
        }
        authenticate(newMaker);
        for (Target target : oldVersionTargets) withCompany(f.company(), () -> guard.check(target.table(), target.id()));
        evidence("current-maker-and-old-version-scope.json", map("beforeActualHttp", oldVisibleBefore,
                "oldMakerAfterActualHttp", oldAfter, "newMakerAfterActualHttp", newAfter,
                "directRealPgGuardChecks", actualGuards,
                "directGuardNote", "Authenticated service guard checks use real PG plus a test request context; public HTTP SELF remains 403",
                "storedAfter", storedRoot(sample)));
    }

    @Test void realHttpCompanyMoveCannotChangeAnAlreadyAuthorizedRepeatableReadPage() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f), reader = auditReader(f.company());
        Map<String, Object> body = sampleBody(f, maker);
        JsonNode made = createSample(maker, body); long sample = id(made.path("id"));
        Gate gate = new Gate(sample); probe.gates.put(gate.key, gate);
        String readTrace = trace("snapshot"), writeTrace = trace("company-move");
        Map<String, Object> facts = map("sampleId", Long.toString(sample), "readCompany", Long.toString(f.company()),
                "moveCompany", Long.toString(f.otherCompany()));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = workers.submit(() -> traced(reader, "GET", auditPath("dev_sample", sample, true), null,
                    f.company(), null, readTrace, gate.key, null));
            try {
                assertThat(gate.ready.await(15, TimeUnit.SECONDS)).as("actual sample guard completed its database authorization").isTrue();
                facts.put("readerConnection", gate.reader);
                Map<String, Object> move = updateBody(body, made.path("version").intValue()); move.put("companyId", f.otherCompany());
                HttpResponse<byte[]> write = traced(maker, "PUT", samplePath(sample, f.company()), move, f.company(), key(), writeTrace, null, gate.key);
                facts.put("actualWriterHttpResponse", node(write)); facts.put("writerConnection", gate.writer);
                success(write);
                long committed = jdbc.queryForObject("SELECT company_id FROM dev_sample WHERE id=?", Long.class, sample);
                facts.put("committedCompanyBeforeResume", Long.toString(committed)); gate.resume.countDown();
                JsonNode read = node(response.get(25, TimeUnit.SECONDS)); facts.put("actualReaderHttpResponse", read);
                facts.put("writerStoredEvents", storedTrace(writeTrace));
                boolean sawWriter = read.path("data").valueStream().anyMatch(row -> row.path("requestId").asText().equals(writeTrace));
                facts.put("readSawCommittedCompanyBEvents", sawWriter);
                // READ_COMMITTED 负控也先完成真正并发与原始证据，再在相同断言处失败。
                evidence("real-http-company-interleaving.json", facts);
                assertThat(gate.writer).isNotNull();
                assertThat(gate.writer.get("pid")).isNotEqualTo(gate.reader.get("pid"));
                assertThat(committed).isEqualTo(f.otherCompany()); assertThat(read.path("code").intValue()).isZero();
                assertThat(sawWriter).as("company A authorization and page must use the same actual PG snapshot").isFalse();
                assertThat(gate.reader.get("isolation")).isEqualTo("repeatable read");
                assertThat(sampleCall(reader, "GET", auditPath("dev_sample", sample, true), null, f.company(), null).path("code").intValue()).isEqualTo(403);
                JsonNode after = history(reader, "dev_sample", sample, true, f.otherCompany());
                assertThat(after.valueStream().anyMatch(row -> row.path("requestId").asText().equals(writeTrace))).isTrue();
                assertThat(after.valueStream().filter(row -> COMPOUND_KEYS.containsKey(row.path("table").asText()))
                        .map(row -> row.path("rowKey").path("company_id").asText()).collect(java.util.stream.Collectors.toSet()))
                        .contains(Long.toString(f.company()), Long.toString(f.otherCompany()));
                facts.put("authorizedCompanyBAuditWithBothHistoricalKeys", after);
            } finally {
                gate.resume.countDown(); probe.gates.remove(gate.key);
                if (!response.isDone()) response.cancel(true);
                evidence("real-http-company-interleaving.json", facts);
            }
        }
    }

    @Test void lateReviewFailureRollsBackEveryNewVersionFactAndSameKeyRetryKeepsSafeTrace(CapturedOutput output) throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f);
        long sample = id(createSample(maker, sampleBody(f, maker)).path("id"));
        dispatchInternal(maker, f.company(), sample, maker.id()); completeSend(maker, f.company(), sample);
        JsonNode detail = sampleDetail(maker, f.company(), sample), order = detail.path("currentOrder");
        Map<String, Object> body = map("version", order.path("version").intValue(), "sampleVersionLock", detail.path("version").intValue(),
                "result", "REVISE", "opinion", "Synthetic revision rollback", "reviewDate", today(), "reviewerIds", List.of(maker.id()), "photos", List.of());
        String path = "/api/sample-orders/" + id(order.path("id")) + "/review?companyId=" + f.company();
        String failedTrace = trace("review-rollback"), retryTrace = trace("review-retry"), requestKey = key();
        Map<String, Object> before = actualState(sample);
        String function = "s4_sample_failure_" + suffix();
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.doc_type='SAMPLE' AND NEW.doc_id=" + sample
                + " AND NEW.action='review' THEN PERFORM pg_sleep(.05); RAISE EXCEPTION 'password=S4-Synthetic-Sample-3927 bankAccount=6222023333333333333 SELECT sample_private_probe'; END IF; RETURN NEW; END $$");
        JsonNode failed;
        try {
            jdbc.execute("CREATE TRIGGER " + function + " AFTER INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            failed = node(traced(maker, "POST", path, body, f.company(), requestKey, failedTrace));
            assertThat(failed.path("code").intValue()).isEqualTo(500);
            assertThat(failed.path("msg").asText()).contains(failedTrace);
            assertThat(failed.toString()).doesNotContain("S4-Synthetic-Sample-3927", "6222023333333333333", "sample_private_probe",
                    "biz_doc_log", "PSQLException", "SQLException", "stackTrace", "INSERT INTO");
            Map<String, Object> after = actualState(sample);
            assertThat(after).isEqualTo(before);
            long receiptRows = jdbc.queryForObject("SELECT count(*) FROM biz_http_action WHERE tenant_code=? AND user_id=? AND operation='SAMPLE_REVIEW' AND request_key=?::uuid",
                    Long.class, namespace.tenant(), maker.id(), requestKey);
            assertThat(receiptRows).isZero(); assertThat(storedTrace(failedTrace)).isEmpty(); assertThat(timeline(failedTrace)).isEmpty();
            List<Map<String, Object>> failureOperation = operations(failedTrace);
            assertThat(failureOperation).hasSize(1); assertThat(failureOperation.getFirst().get("success")).isEqualTo(false);
            assertThat(json.writeValueAsString(failureOperation)).doesNotContain("S4-Synthetic-Sample-3927", "6222023333333333333", "sample_private_probe");
            assertThat(output.getAll()).doesNotContain("S4-Synthetic-Sample-3927", "6222023333333333333", "SELECT sample_private_probe");
            List<String> slow = output.getAll().lines().filter(line -> line.contains(failedTrace) && line.contains("slow_sql")).toList();
            assertThat(slow).isNotEmpty();
            evidence("late-review-rollback.json", map("failedHttpResponse", failed, "beforeActualCountsAndState", before,
                    "afterActualCountsAndState", after, "remainingReceiptRows", receiptRows, "remainingFieldAudit", storedTrace(failedTrace),
                    "remainingBusinessAudit", timeline(failedTrace), "actualFailureOperation", failureOperation, "actualSafeSlowSql", slow));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON biz_doc_log"); jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
        JsonNode recovered = success(traced(maker, "POST", path, body, f.company(), requestKey, retryTrace));
        assertThat(recovered.path("sampleVersion").intValue()).isEqualTo(2);
        assertThat(storedTrace(failedTrace)).isEmpty(); assertThat(timeline(failedTrace)).isEmpty();
        assertThat(storedTrace(retryTrace)).isNotEmpty(); assertThat(timeline(retryTrace)).hasSize(1);
        evidence("same-key-review-recovery.json", map("actualRetryResponse", recovered, "actualState", actualState(sample),
                "actualFieldAudit", storedTrace(retryTrace), "actualBusinessAudit", timeline(retryTrace), "actualOperationAudit", operations(retryTrace)));
    }

    @Test void creationReplayAddsNoFactsAndDraftTombstoneDoesNotExposeHistoricalSelfOrTagReads() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f), reader = auditReader(f.company());
        Map<String, Object> body = sampleBody(f, maker); String requestKey = key(), firstTrace = trace("first"), replayTrace = trace("replay");
        JsonNode first = success(traced(maker, "POST", "/api/samples", body, f.company(), requestKey, firstTrace));
        JsonNode replay = success(traced(maker, "POST", "/api/samples", body, f.company(), requestKey, replayTrace));
        assertThat(replay).isEqualTo(first);
        long sample = id(first.path("id")), order = id(first.path("currentOrderId"));
        assertThat(storedTrace(firstTrace)).isNotEmpty(); assertThat(timeline(firstTrace)).hasSize(1);
        assertThat(storedTrace(replayTrace)).isEmpty(); assertThat(timeline(replayTrace)).isEmpty();
        assertThat(operations(replayTrace)).hasSize(1); assertThat(operations(replayTrace).getFirst().get("success")).isEqualTo(true);
        attachTag(maker, "SAMPLE", sample, f.company());
        long object = registry("SAMPLE", sample), link = firstLink(object);
        String deleteTrace = trace("tombstone");
        success(traced(maker, "DELETE", samplePath(sample, f.company()) + "&version=" + first.path("version").intValue(),
                null, f.company(), key(), deleteTrace));
        String deletedToken = jdbc.queryForObject("SELECT deleted_token::text FROM dev_sample WHERE id=?", String.class, sample);
        assertThat(deletedToken).isEqualTo(Long.toString(sample));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE id=? AND sample_id=?", Long.class, order, sample)).isOne();
        List<Map<String, Object>> deleted = storedTrace(deleteTrace);
        assertThat(deleted.stream().filter(row -> row.get("table").equals("dev_sample")))
                .allSatisfy(row -> assertThat(row.get("action")).isEqualTo("UPDATE"));
        JsonNode deletedJson = json.valueToTree(deleted);
        JsonNode tombstone = deletedJson.valueStream().filter(row -> row.path("table").asText().equals("dev_sample")).findFirst().orElseThrow();
        assertThat(tombstone.path("diff").path("deleted_token").path("new").asText()).isEqualTo(Long.toString(sample));
        assertThat(deleted.stream().filter(row -> row.get("table").equals("biz_tag_link")).map(row -> row.get("action"))).contains("DELETE");
        for (Target target : List.of(new Target("dev_sample", sample, true), new Target("dev_sample_order", order, false),
                new Target("biz_tag_object", object, false), new Target("biz_tag_link", link, false))) {
            assertThat(sampleCall(reader, "GET", auditPath(target.table(), target.id(), target.root()), null, f.company(), null).path("code").intValue()).isEqualTo(404);
            assertThat(sampleCall(reader, "GET", auditPath(target.table(), target.id(), target.root()) + "&beforeId=1", null, f.company(), null).path("code").intValue()).isEqualTo(404);
        }
        evidence("replay-and-retained-tombstone.json", map("firstReceipt", first, "replayedReceipt", replay,
                "firstFieldAudit", storedTrace(firstTrace), "replayFieldAudit", storedTrace(replayTrace), "replayBusinessAudit", timeline(replayTrace),
                "actualReplayOperation", operations(replayTrace), "deletedToken", deletedToken, "actualDeleteAudit", deleted,
                "actualDeleteTimeline", timeline(deleteTrace), "retainedActualRows", actualState(sample)));
    }

    @Test void packAuditFollowsActualSampleOwnerBeforeConversionAndExistingSharedStyleOwnerAfterward() throws Exception {
        Fixture f = fixture(); Account maker = sampleActor(f);
        Account sampleAndPack = custom(f.company(), "ALL", "system:audit:query", "dev:sample:list", "techpack:pack:list");
        Account packOnly = custom(f.company(), "ALL", "system:audit:query", "techpack:pack:list");
        Account noPack = custom(f.company(), "ALL", "system:audit:query", "dev:sample:list");
        long sample = id(createSample(maker, sampleBody(f, maker)).path("id"));
        long pack = id(sampleOk(maker, "POST", "/api/tech-packs", map("sampleId", sample, "companyId", f.company(), "stage", "DEV"), f.company()));
        attachTag(maker, "TECH_PACK", pack, f.company());
        long object = registry("TECH_PACK", pack), link = firstLink(object);
        List<Target> targets = List.of(new Target("tp_tech_pack", pack, true), new Target("tp_tech_pack", pack, false),
                new Target("biz_tag_object", object, false), new Target("biz_tag_link", link, false));
        long tagVersion = jdbc.queryForObject("SELECT version FROM biz_tag_object WHERE id=?", Long.class, object);
        List<Map<String, Object>> before = new ArrayList<>();
        for (Target target : targets) {
            String path = auditPath(target.table(), target.id(), target.root());
            JsonNode visible = sampleOk(sampleAndPack, "GET", path, null, f.company());
            JsonNode denied = sampleCall(packOnly, "GET", path + "&beforeId=1", null, f.company(), null);
            assertThat(denied.path("code").intValue()).isEqualTo(403);
            assertThat(sampleCall(sampleAndPack, "GET", path, null, f.otherCompany(), null).path("code").intValue()).isEqualTo(403);
            assertThat(sampleCall(noPack, "GET", path, null, f.company(), null).path("code").intValue()).isEqualTo(403);
            before.add(map("target", target, "visible", visible, "noSamplePermission", denied));
        }
        dispatchInternal(maker, f.company(), sample, maker.id()); completeSend(maker, f.company(), sample);
        reviewSample(maker, f.company(), sample, "PASS");
        JsonNode detail = sampleDetail(maker, f.company(), sample);
        String styleClass = jdbc.queryForObject("SELECT value FROM sys_dict_data WHERE type_code='style_class' AND status=1 AND NOT deleted ORDER BY sort,id LIMIT 1", String.class);
        String convertTrace = trace("owner-convert");
        JsonNode converted = success(traced(maker, "POST", "/api/samples/" + sample + "/convert?companyId=" + f.company(),
                map("version", detail.path("version").intValue(), "codeMode", "MANUAL", "code", "S4CV" + suffix().toUpperCase(java.util.Locale.ROOT),
                        "styleClass", styleClass, "name", "S4 converted audit sample"), f.company(), key(), convertTrace));
        long style = id(converted.path("styleId"));
        Map<String, Object> actualOwner = jdbc.queryForMap("SELECT id::text,style_id::text,sample_id::text FROM tp_tech_pack WHERE id=?", pack);
        assertThat(actualOwner.get("style_id")).isEqualTo(Long.toString(style));
        assertThat(actualOwner.get("sample_id")).isEqualTo(Long.toString(sample));
        Map<String, Object> actualRegistry = jdbc.queryForMap("SELECT id::text,company_id::text,version FROM biz_tag_object WHERE id=?", object);
        assertThat(actualRegistry.get("company_id")).isNull();
        assertThat(((Number) actualRegistry.get("version")).longValue()).isEqualTo(tagVersion);
        List<Map<String, Object>> after = new ArrayList<>();
        for (Target target : targets) {
            String path = auditPath(target.table(), target.id(), target.root());
            // 款式资料包沿既有共享域权限；不虚构 company ACL，也不新增 style:list 的额外门禁。
            JsonNode visible = sampleOk(packOnly, "GET", path, null, f.otherCompany());
            assertThat(sampleCall(noPack, "GET", path, null, f.company(), null).path("code").intValue()).isEqualTo(403);
            after.add(map("target", target, "sharedStyleOwnerVisibleWithoutSampleGrant", visible));
        }
        JsonNode ownerEvents = history(packOnly, "biz_tag_object", object, false, null);
        JsonNode move = byTrace(ownerEvents, convertTrace, "biz_tag_object").path("diff").path("company_id");
        assertThat(move.path("old").asText()).isEqualTo(Long.toString(f.company())); assertThat(move.path("new").isNull()).isTrue();
        assertThat(sampleCall(packOnly, "GET", auditPath("dev_sample", sample, true), null, f.company(), null).path("code").intValue()).isEqualTo(403);
        evidence("actual-pack-owner-transfer.json", map("sampleId", Long.toString(sample), "beforeConversion", before,
                "actualConversionReceipt", converted, "actualPackOwner", actualOwner, "actualTagRegistry", actualRegistry,
                "afterConversion", after, "registryHistoricalCompanyChange", ownerEvents, "actualConvertFieldAudit", storedTrace(convertTrace)));
    }

    private record Target(String table, long id, boolean root) { }
    private Account auditReader(long company, String... prices) throws Exception {
        List<String> grants = new ArrayList<>(List.of("system:audit:query", "dev:sample:list")); grants.addAll(List.of(prices));
        return custom(company, "ALL", grants.toArray(String[]::new));
    }
    private static Map<String, Object> updateBody(Map<String, Object> source, int version) {
        Map<String, Object> result = new LinkedHashMap<>(source); result.remove("needDate"); result.remove("images"); result.put("version", version); return result;
    }
    private List<Target> scalarTargets(long sample) {
        long order = jdbc.queryForObject("SELECT id FROM dev_sample_order WHERE sample_id=? ORDER BY sample_version LIMIT 1", Long.class, sample);
        long node = jdbc.queryForObject("SELECT min(id) FROM dev_sample_node WHERE sample_order_id=?", Long.class, order);
        long review = jdbc.queryForObject("SELECT id FROM dev_sample_review WHERE sample_order_id=?", Long.class, order);
        return List.of(new Target("dev_sample", sample, false), new Target("dev_sample_order", order, false),
                new Target("dev_sample_node", node, false), new Target("dev_sample_review", review, false));
    }
    private JsonNode attachTag(Account maker, String type, long id, long company) throws Exception {
        return sampleOk(maker, "POST", "/api/tags/objects/" + type + "/" + id + "/replace",
                map("version", 0, "names", List.of("s4-sample-" + suffix()), "tagVersions", Map.of(), "retainIds", List.of()), company);
    }
    private long registry(String type, long id) { return jdbc.queryForObject("SELECT id FROM biz_tag_object WHERE tenant_key=? AND object_type=? AND object_id=?", Long.class, namespace.tenant(), type, id); }
    private long firstLink(long object) { return jdbc.queryForObject("SELECT id FROM biz_tag_link WHERE tenant_key=? AND object_ref_id=? ORDER BY id LIMIT 1", Long.class, namespace.tenant(), object); }
    private static String samplePath(long id, long company) { return "/api/samples/" + id + "?companyId=" + company; }
    private static String auditPath(String table, long id, boolean root) { return "/api/data-logs?" + (root ? "rootTable=" : "table=") + table + (root ? "&rootId=" : "&rowId=") + id + "&limit=100"; }
    private JsonNode history(Account account, String table, long id, boolean root, Long company) throws Exception { return sampleOk(account, "GET", auditPath(table, id, root), null, company); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String trace(String action) { return "s4-sample-" + action + "-" + UUID.randomUUID(); }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, Long company, String key, String trace) throws Exception {
        return traced(actor, method, path, body, company, key, trace, null, null);
    }
    private HttpResponse<byte[]> traced(Account actor, String method, String path, Object body, Long company, String key, String trace, String readProbe, String writeProbe) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json").header("X-Request-Id", trace);
        if (company != null) request.header("X-Company-Id", company.toString());
        if (key != null) request.header("Idempotency-Key", key);
        if (readProbe != null) request.header("X-S4-Sample-Audit-Probe", readProbe);
        if (writeProbe != null) request.header("X-S4-Sample-Write-Probe", writeProbe);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        synchronized (sampleHttpEvidence) {
            sampleHttpEvidence.add(map("method", method, "path", path, "companyId", company, "requestId", trace,
                    "requestBody", body, "httpStatus", response.statusCode(), "response", node(response)));
        }
        return response;
    }
    private JsonNode success(HttpResponse<byte[]> response) {
        JsonNode reply = node(response); assertThat(reply.path("code").intValue()).as(reply.toString()).isZero(); return reply.path("data");
    }
    private static JsonNode byTrace(JsonNode rows, String trace, String table) {
        List<JsonNode> selected = rows.valueStream().filter(row -> row.path("requestId").asText().equals(trace) && row.path("table").asText().equals(table)).toList();
        assertThat(selected).hasSize(1); return selected.getFirst();
    }
    private void assertIdsAndKeys(JsonNode rows, long sample) {
        for (JsonNode row : rows) {
            for (String key : List.of("id", "rootId", "transactionId", "actorUserId")) assertThat(row.path(key).isString()).as(key).isTrue();
            assertThat(row.path("rootTable").asText()).isEqualTo("dev_sample"); assertThat(row.path("rootId").asText()).isEqualTo(Long.toString(sample));
            String table = row.path("table").asText();
            if (COMPOUND_KEYS.containsKey(table)) {
                assertThat(row.path("rowId").isNull()).isTrue(); JsonNode keys = row.path("rowKey");
                assertThat(keys.propertyNames()).containsExactlyInAnyOrderElementsOf(COMPOUND_KEYS.get(table));
                assertThat(keys.path("tenant_key").asText()).isEqualTo(namespace.tenant());
                keys.properties().forEach(field -> assertThat(field.getValue().isString()).as(field.getKey()).isTrue());
            } else { assertThat(row.path("rowId").isString()).isTrue(); assertThat(row.path("rowKey").isNull()).isTrue(); }
            for (var field : row.path("diff").properties()) if (field.getKey().equals("id") || field.getKey().equals("deleted_token")
                    || field.getKey().endsWith("_id") || field.getKey().endsWith("_by")) {
                for (String side : List.of("old", "new")) if (!field.getValue().path(side).isNull()) assertThat(field.getValue().path(side).isString()).as(field.getKey()).isTrue();
            }
        }
    }
    private List<Map<String, Object>> storedRoot(long sample) { return stored("root_table='dev_sample' AND root_id=?", sample); }
    private List<Map<String, Object>> storedTrace(String trace) { return stored("request_id=?", trace); }
    private List<Map<String, Object>> stored(String fixedPredicate, Object value) {
        return jdbc.query("SELECT id,tenant_key,table_name,row_id,row_key,root_table,root_id,action,diff,actor_user_id,request_id,transaction_id FROM sys_data_log WHERE tenant_key=? AND "
                        + fixedPredicate + " ORDER BY id LIMIT 500", (rs, n) -> map("id", rs.getString("id"), "namespace", rs.getString("tenant_key"),
                        "table", rs.getString("table_name"), "rowId", rs.getString("row_id"), "rowKey", rs.getString("row_key") == null ? null : json.readTree(rs.getString("row_key")),
                        "rootTable", rs.getString("root_table"), "rootId", rs.getString("root_id"), "action", rs.getString("action"), "diff", json.readTree(rs.getString("diff")),
                        "actorUserId", rs.getString("actor_user_id"), "requestId", rs.getString("request_id"), "transactionId", rs.getString("transaction_id")), namespace.tenant(), value);
    }
    private List<Map<String, Object>> timeline(String trace) { return jdbc.queryForList("SELECT id::text,doc_type,doc_id::text,action,operator_id::text,request_id FROM biz_doc_log WHERE request_id=? ORDER BY id", trace); }
    private List<Map<String, Object>> operations(String trace) { return jdbc.queryForList("SELECT id::text,module,action,success,trace_id,error_msg FROM sys_oper_log WHERE trace_id=? ORDER BY id", trace); }
    private Map<String, Object> actualState(long sample) {
        return map("sample", jdbc.queryForMap("SELECT id::text,company_id::text,status,version,deleted_token::text FROM dev_sample WHERE id=?", sample),
                "orders", jdbc.queryForList("SELECT id::text,sample_version,status,version,maker_id::text FROM dev_sample_order WHERE sample_id=? ORDER BY sample_version", sample),
                "colors", jdbc.queryForObject("SELECT count(*) FROM dev_sample_color WHERE sample_id=?", Long.class, sample),
                "orderColors", jdbc.queryForObject("SELECT count(*) FROM dev_sample_order_color c JOIN dev_sample_order o ON o.id=c.sample_order_id WHERE o.sample_id=?", Long.class, sample),
                "orderSizes", jdbc.queryForObject("SELECT count(*) FROM dev_sample_order_size z JOIN dev_sample_order o ON o.id=z.sample_order_id WHERE o.sample_id=?", Long.class, sample),
                "nodes", jdbc.queryForObject("SELECT count(*) FROM dev_sample_node n JOIN dev_sample_order o ON o.id=n.sample_order_id WHERE o.sample_id=?", Long.class, sample),
                "reviews", jdbc.queryForObject("SELECT count(*) FROM dev_sample_review r JOIN dev_sample_order o ON o.id=r.sample_order_id WHERE o.sample_id=?", Long.class, sample),
                "reviewers", jdbc.queryForObject("SELECT count(*) FROM dev_sample_reviewer v JOIN dev_sample_review r ON r.id=v.review_id JOIN dev_sample_order o ON o.id=r.sample_order_id WHERE o.sample_id=?", Long.class, sample),
                "fieldAudit", jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE tenant_key=? AND root_table='dev_sample' AND root_id=?", Long.class, namespace.tenant(), sample),
                "businessAudit", jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=?", Long.class, sample));
    }
    private static void withCompany(long company, Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data-logs"); request.addHeader("X-Company-Id", Long.toString(company));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { action.run(); } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private void evidence(String name, Object value) throws Exception {
        Path path = Path.of("test-artifacts/full-audit/sample/s4-http", name); Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /** 只有本 IT 导入；生产没有暂停请求头、Mock 查询或替换事务的代码。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean SampleProbe sampleAuditProbe() { return new SampleProbe(); }
        @Bean ProbeAspect sampleAuditProbeAspect(SampleProbe probe, JdbcTemplate jdbc) { return new ProbeAspect(probe, jdbc); }
    }
    static class SampleProbe { final Map<String, Gate> gates = new ConcurrentHashMap<>(); }
    static class Gate {
        final String key = UUID.randomUUID().toString(); final long sample;
        final CountDownLatch ready = new CountDownLatch(1), resume = new CountDownLatch(1); final AtomicBoolean used = new AtomicBoolean();
        volatile Map<String, Object> reader, writer;
        Gate(long sample) { this.sample = sample; }
    }
    @Aspect
    static class ProbeAspect {
        private final SampleProbe probe; private final JdbcTemplate jdbc;
        ProbeAspect(SampleProbe probe, JdbcTemplate jdbc) { this.probe = probe; this.jdbc = jdbc; }
        @Around(value = "execution(* com.lingmao.scm.module.dev.service.impl.SampleAuditTargetGuard.check(..)) && args(table,id,historical)",
                argNames = "point,table,id,historical")
        Object afterAuthorization(ProceedingJoinPoint point, String table, long id, AuditTargetGuard.HistoricalRootGuard historical) throws Throwable {
            Object actual = point.proceed(); Gate gate = gate("X-S4-Sample-Audit-Probe");
            if (gate == null || !table.equals("dev_sample") || gate.sample != id || !gate.used.compareAndSet(false, true)) return actual;
            gate.reader = connection(); gate.ready.countDown();
            if (!gate.resume.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("sample audit test barrier timed out");
            return actual;
        }
        @Around("execution(* com.lingmao.scm.module.dev.repository.SampleRepository.updateSample(..))")
        Object captureActualWriter(ProceedingJoinPoint point) throws Throwable {
            Gate gate = gate("X-S4-Sample-Write-Probe"); if (gate != null) gate.writer = connection(); return point.proceed();
        }
        private Gate gate(String header) {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) return null;
            String key = request.getRequest().getHeader(header); return key == null ? null : probe.gates.get(key);
        }
        private Map<String, Object> connection() {
            return jdbc.queryForMap("SELECT pg_backend_pid() AS pid,current_setting('transaction_isolation') AS isolation,txid_current()::text AS transaction_id");
        }
    }
}
