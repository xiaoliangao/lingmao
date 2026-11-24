package com.lingmao.scm.security;

import com.lingmao.scm.module.biz.repository.NoticeSummaryMaintenance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 真 TCP + PostgreSQL：聚合口径、授权、分页、排序、ID 协议均通过实际响应验收。 */
class S2BoardHttpIT extends A2BusinessFixture {
    @Autowired NoticeSummaryMaintenance noticeSummary;

    @Test void homeBlocksHaveRealValuesAndOmitUnauthorizedDomains() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        long order = sourceOrder(f, owner, "APPROVED", f.company());
        jdbc.update("UPDATE ord_order SET required_date=?,commit_date=? WHERE id=?", today(), today(), order);
        JsonNode kpi = ok(owner, "GET", "/api/board/home/kpi", null, f.company());
        assertThat(kpi.path("order").path("qty").longValue()).isEqualTo(10);
        assertThat(kpi.path("order").path("count").longValue()).isEqualTo(1);
        assertThat(kpi.path("dev").isNull()).isTrue();
        assertThat(kpi.path("production").isNull()).isTrue();
        assertThat(kpi.path("material").isNull()).isTrue();
        JsonNode deliveries = ok(owner, "GET", "/api/board/home/deliveries", null, f.company());
        assertThat(deliveries.size()).isEqualTo(1);
        assertThat(deliveries.get(0).path("ref").path("id").isString()).isTrue();
        assertThat(id(deliveries.get(0).path("ref").path("id"))).isEqualTo(order);
        assertThat(deliveries.toString()).doesNotContain("price", "totalAmount", "refCost");
        assertThat(call(owner, "GET", "/api/board/home/todos?ownerId=not-a-long", null, f.company()).path("code").intValue()).isEqualTo(400);
        Account none = custom(f.company(), "SELF");
        JsonNode empty = ok(none, "GET", "/api/board/home/kpi", null, f.company());
        for (String block : List.of("dev", "order", "production", "material")) assertThat(empty.path(block).isNull()).isTrue();
        assertThat(ok(none, "GET", "/api/board/home/todos", null, f.company()).path("groups").isEmpty()).isTrue();
        evidence("low-permission-kpi.json", kpi);
        evidence("low-permission-deliveries.json", deliveries);
    }

    @Test void oldestSubmittedAuditIsSelectedBeforeLimitAndReviewerExceptionDoesNotGrantAll() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        Account reviewer = custom(f.company(), "SELF", "order:order:list", "order:order:audit");
        Account stranger = custom(f.company(), "SELF", "order:order:list", "order:order:audit");
        OffsetDateTime anchor = today().atStartOfDay().atOffset(ZoneOffset.UTC).minusDays(90);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long order = sourceOrder(f, owner, "PENDING", f.company());
            expected.add(order);
            // Creation order intentionally disagrees with submission order.
            jdbc.update("UPDATE ord_order SET create_time=? WHERE id=?", anchor.plusDays(60 - i), order);
            jdbc.update("INSERT INTO biz_doc_log(doc_type,doc_id,action,to_status,create_time) VALUES('ORDER',?,'submit','PENDING',?)", order, anchor.plusDays(i));
            jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES('ORDER',?,1,1,?,'CURRENT')", order, reviewer.id());
        }
        jdbc.update("""
                WITH generated AS (
                    INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,create_by)
                    SELECT ? || n,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,?,?,'PENDING',0,? FROM generate_series(1,1000) n
                    RETURNING id
                ) INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status)
                  SELECT 'ORDER',id,1,1,?,'CURRENT' FROM generated
                """, "S2QUEUE" + suffix(), f.company(), owner.id(), owner.id(), owner.id(), reviewer.id());
        long wrongPermission = sourceOrder(f, owner, "PENDING", f.company());
        jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,perm,status) VALUES('ORDER',?,1,1,'purchase:order:audit','CURRENT')", wrongPermission);
        JsonNode todos = ok(reviewer, "GET", "/api/board/home/todos", null, f.company());
        JsonNode audit = group(todos, "AUDIT");
        assertThat(audit.path("total").longValue()).isEqualTo(1025);
        assertThat(audit.path("items").size()).isEqualTo(20);
        List<Long> actual = new ArrayList<>();
        audit.path("items").forEach(item -> actual.add(id(item.path("ref").path("id"))));
        assertThat(actual).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(todos.path("canSeeAll").booleanValue()).isFalse();
        JsonNode narrowed = ok(reviewer, "GET", "/api/board/home/todos?mine=true&ownerId=" + owner.id(), null, f.company());
        assertThat(group(narrowed, "AUDIT").path("total").longValue()).isEqualTo(1025);
        assertThat(ok(reviewer, "GET", "/api/orders", null, f.company()).path("total").longValue()).isZero();
        long order = expected.getFirst();
        JsonNode review = ok(reviewer, "GET", "/api/board/orders/" + order + "/review", null, f.company());
        assertThat(review.path("packs").size()).isEqualTo(1);
        assertThat(review.path("packs").get(0).path("techPackId").isString()).isTrue();
        assertThat(review.path("shortages").get(0).path("gap").decimalValue()).isEqualByComparingTo("15.45");
        assertThat(review.toString()).doesNotContain("price", "refCost", "bankAccount", "totalAmount");
        assertThat(call(stranger, "GET", "/api/board/orders/" + order + "/review", null, f.company()).path("code").intValue()).isEqualTo(404);
        Account noList = custom(f.company(), "ALL", "order:order:audit");
        assertThat(call(noList, "GET", "/api/board/orders/" + order + "/review", null, f.company()).path("code").intValue()).isEqualTo(403);
        evidence("current-reviewer-review.json", review);
        evidence("oldest-1025-audit-preview.json", todos);
    }

    @Test void upstreamSeeksAuthorizedGroupsAndCapsPerDocumentWithoutLosingRemainingNotices() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "purchase:order:list", "purchase:order:edit");
        Account foreign = custom(f.company(), "SELF", "purchase:order:list");
        Account boss = custom(f.company(), "ALL", "purchase:order:list");
        long source = sourceOrder(f, owner, "APPROVED", f.company());
        OffsetDateTime time = today().atStartOfDay().atOffset(ZoneOffset.UTC);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 121; i++) {
            long purchase = sourcePurchase(f, owner); ids.add(purchase);
            notice(purchase, source, foreign.id(), time); // stale snapshot owner must not drive visibility.
        }
        long big = sourcePurchase(f, owner); ids.add(big);
        for (int i = 0; i < 125; i++) notice(big, source, foreign.id(), time.plusSeconds(i + 1));
        long hidden = sourcePurchase(f, foreign); notice(hidden, source, owner.id(), time.plusYears(1));
        JsonNode first = ok(owner, "GET", "/api/board/upstream?size=50", null, f.company());
        assertThat(first.path("total").longValue()).isEqualTo(122);
        assertThat(first.path("rows").size()).isEqualTo(50);
        JsonNode firstRow = first.path("rows").get(0);
        assertThat(id(firstRow.path("doc").path("id"))).isEqualTo(big);
        assertThat(firstRow.path("noticesTotal").longValue()).isEqualTo(125);
        assertThat(firstRow.path("notices").size()).isEqualTo(100);
        Set<Long> found = new HashSet<>(); JsonNode page = first;
        while (true) {
            page.path("rows").forEach(row -> assertThat(found.add(id(row.path("doc").path("id")))).isTrue());
            if (!page.path("hasMore").booleanValue()) break;
            String cursor = page.path("nextCursor").stringValue();
            page = ok(owner, "GET", "/api/board/upstream?size=50&cursor=" + cursor, null, f.company());
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        String cursor = first.path("nextCursor").stringValue();
        assertThat(call(owner, "GET", "/api/board/upstream?size=51&cursor=" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(call(owner, "GET", "/api/board/upstream?size=50&cursor=x" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(call(owner, "GET", "/api/board/upstream?size=101", null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(ok(foreign, "GET", "/api/board/upstream?ownerId=" + owner.id(), null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(ok(boss, "GET", "/api/board/upstream?ownerId=" + owner.id(), null, f.company()).path("total").longValue()).isEqualTo(122);
        assertThat(ok(boss, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();
        JsonNode home = group(ok(owner, "GET", "/api/board/home/todos", null, f.company()), "UPSTREAM");
        assertThat(home.path("total").longValue()).isEqualTo(122);
        assertThat(home.path("items").size()).isEqualTo(20);
        // Real domain action processes the returned preview only. 25 unread records still exist.
        for (JsonNode notice : firstRow.path("notices")) {
            ok(owner, "POST", "/api/purchase-orders/" + big + "/notices/" + notice.path("id").stringValue() + "/handle",
                    Map.of("remark", "S2 preview handled"), f.company());
        }
        JsonNode after = ok(owner, "GET", "/api/board/upstream?size=100", null, f.company());
        JsonNode remaining = null;
        for (JsonNode row : after.path("rows")) if (id(row.path("doc").path("id")) == big) remaining = row;
        assertThat(remaining).isNotNull();
        assertThat(remaining.path("noticesTotal").longValue()).isEqualTo(25);
        assertThat(remaining.path("notices").size()).isEqualTo(25);
        evidence("upstream-preview-first-page.json", first);
        evidence("upstream-after-preview-handled.json", after);
    }

    @Test void selfCreatorVisibilitySurvivesDelegatedOwnerButMineNarrowingAndModuleAclRemainEffective() throws Exception {
        Fixture f = fixture();
        Account creator = custom(f.company(), "SELF", "purchase:order:list");
        Account buyer = custom(f.company(), "SELF", "purchase:order:list");
        long source = sourceOrder(f, creator, "APPROVED", f.company());
        long purchase = sourcePurchase(f, creator);
        jdbc.update("UPDATE pur_order SET buyer_id=? WHERE id=?", buyer.id(), purchase);
        notice(purchase, source, creator.id(), today().atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThat(ok(creator, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(ok(creator, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();
        assertThat(ok(creator, "GET", "/api/board/upstream?ownerId=" + buyer.id(), null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(group(ok(creator, "GET", "/api/board/home/todos", null, f.company()), "UPSTREAM").path("total").longValue()).isEqualTo(1);
        Account none = custom(f.company(), "ALL", "order:order:audit");
        assertThat(ok(none, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isZero();
    }

    @Test void summaryReadsFollowCurrentOwnerCompanyAndParentExistenceAndRemainEquivalentAfterFallback() throws Exception {
        Fixture f = fixture();
        Account creator = custom(f.company(), "SELF", "purchase:order:list");
        Account firstBuyer = custom(f.company(), "SELF", "purchase:order:list");
        Account secondBuyer = custom(f.company(), "SELF", "purchase:order:list");
        Account otherCompanyBuyer = custom(f.otherCompany(), "SELF", "purchase:order:list");
        Account noModule = custom(f.company(), "ALL", "order:order:list");
        long source = sourceOrder(f, creator, "APPROVED", f.company());
        long purchase = sourcePurchase(f, creator);
        jdbc.update("UPDATE pur_order SET buyer_id=? WHERE id=?", firstBuyer.id(), purchase);
        notice(purchase, source, firstBuyer.id(), today().atStartOfDay().atOffset(ZoneOffset.UTC));
        var saved = noticeSummary.state();
        try (AutoCloseable cleanup = () -> cleanupSummaryFixture(purchase, saved)) {
            noticeSummary.setUseSummary(false);
            JsonNode sourcePage = ok(firstBuyer, "GET", "/api/board/upstream", null, f.company());
            assertThat(sourcePage.path("total").longValue()).isEqualTo(1);
            noticeSummary.restartBackfill();
            finishSummaryPhase(false);
            noticeSummary.startReconciliation();
            finishSummaryPhase(true);
            assertThat(noticeSummary.state().ready()).isTrue();
            noticeSummary.setUseSummary(true);
            assertThat(ok(firstBuyer, "GET", "/api/board/upstream", null, f.company())).isEqualTo(sourcePage);
            assertThat(ok(noModule, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isZero();

            jdbc.update("UPDATE pur_order SET buyer_id=? WHERE id=?", secondBuyer.id(), purchase);
            assertThat(jdbc.queryForObject("SELECT owner_id FROM biz_doc_notice WHERE doc_type='PURCHASE_ORDER' AND doc_id=?", Long.class, purchase)).isEqualTo(firstBuyer.id());
            assertThat(ok(firstBuyer, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isZero();
            JsonNode movedOwner = ok(secondBuyer, "GET", "/api/board/upstream", null, f.company());
            assertThat(movedOwner.path("total").longValue()).isEqualTo(1);
            assertThat(id(movedOwner.path("rows").get(0).path("doc").path("id"))).isEqualTo(purchase);
            assertThat(ok(creator, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isEqualTo(1);
            assertThat(ok(creator, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();

            jdbc.update("UPDATE pur_order SET company_id=?,buyer_id=? WHERE id=?", f.otherCompany(), otherCompanyBuyer.id(), purchase);
            assertThat(jdbc.queryForObject("SELECT company_id FROM pur_order WHERE id=?", Long.class, purchase)).isEqualTo(f.otherCompany());
            assertThat(ok(secondBuyer, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isZero();
            // Companies are shared in the frozen contract; SELF still includes the current creator.
            JsonNode creatorAfterCompanyMove = ok(creator, "GET", "/api/board/upstream", null, f.company());
            assertThat(creatorAfterCompanyMove.path("total").longValue()).isEqualTo(1);
            assertThat(ok(creator, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();
            JsonNode movedCompany = ok(otherCompanyBuyer, "GET", "/api/board/upstream", null, f.otherCompany());
            assertThat(movedCompany.path("total").longValue()).isEqualTo(1);
            noticeSummary.setUseSummary(false);
            assertThat(ok(creator, "GET", "/api/board/upstream", null, f.company())).isEqualTo(creatorAfterCompanyMove);
            assertThat(ok(otherCompanyBuyer, "GET", "/api/board/upstream", null, f.otherCompany())).isEqualTo(movedCompany);
            noticeSummary.setUseSummary(true);

            jdbc.update("DELETE FROM pur_order_item WHERE purchase_order_id=?", purchase);
            assertThat(jdbc.update("DELETE FROM pur_order WHERE id=?", purchase)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT pending_count FROM biz_doc_notice_pending WHERE tenant_key=scm_notice_pending_namespace()
                        AND doc_type='PURCHASE_ORDER' AND doc_id=?
                    """, Long.class, purchase)).isEqualTo(1);
            JsonNode deleted = ok(otherCompanyBuyer, "GET", "/api/board/upstream", null, f.otherCompany());
            assertThat(deleted.path("total").longValue()).isZero();
            assertThat(deleted.path("rows").isEmpty()).isTrue();
            noticeSummary.setUseSummary(false);
            assertThat(ok(otherCompanyBuyer, "GET", "/api/board/upstream", null, f.otherCompany())).isEqualTo(deleted);
            evidence("notice-summary-current-authorization.json", json.valueToTree(Map.of("source", sourcePage,
                    "movedOwner", movedOwner, "movedCompany", movedCompany, "deletedParent", deleted,
                    "creatorAfterCompanyMove", creatorAfterCompanyMove,
                    "staleNoticeOwnerIgnored", true, "positiveOrphanSummaryHidden", true)));
        }
    }

    @Test void summaryHttpReadsStayCommittedDuringAHotKeyWriteTransaction() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "purchase:order:list");
        long source = sourceOrder(f, owner, "APPROVED", f.company());
        long purchase = sourcePurchase(f, owner);
        OffsetDateTime base = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var saved = noticeSummary.state();
        var pool = Executors.newSingleThreadExecutor();
        var ready = new CompletableFuture<MixedWriteReady>();
        var release = new CountDownLatch(1);
        Map<String, Object> result;
        try (AutoCloseable cleanup = () -> cleanupMixedSummary(purchase, saved);
             AutoCloseable stopWriter = () -> {
                 release.countDown();
                 pool.shutdownNow();
                 assertThat(pool.awaitTermination(12, TimeUnit.SECONDS)).as("mixed writer cleanup").isTrue();
             }) {
            for (int first = 1; first <= 100_000; first += 10_000) {
                assertThat(jdbc.update("""
                        INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time)
                        SELECT 'PURCHASE_ORDER',?,'ORDER',?,'P33 mixed committed fixture',?,
                               ?::timestamptz+n*interval '1 second'
                        FROM generate_series(?::integer,?::integer) n
                        """, purchase, source, owner.id(), base, first, first + 9_999)).isEqualTo(10_000);
            }
            noticeSummary.restartBackfill();
            finishSummaryPhase(false);
            noticeSummary.startReconciliation();
            finishSummaryPhase(true);
            noticeSummary.setUseSummary(true);
            assertThat(noticeSummary.readNamespace()).isPresent();

            MixedNoticeState before = mixedSource(purchase);
            assertThat(before.totalRows()).isEqualTo(100_000);
            assertThat(before.pending()).isEqualTo(100_000);
            MixedSummary beforeSummary = mixedSummary(purchase);
            assertMixedSummary(beforeSummary, before);
            MixedHttpRead warmup = mixedRead(owner, f.company()); // Exactly one fixed warmup; no timing-based retries.
            List<String> oldPreview = assertMixedPage(warmup, purchase, before);

            var work = pool.submit(() -> mixedWriter(purchase, source, owner.id(), before, beforeSummary, ready, release));
            MixedWriteReady held = ready.get(10, TimeUnit.SECONDS);
            List<Map<String, Object>> reads = new ArrayList<>();
            long lastReadFinished = 0;
            for (int sample = 0; sample < 3; sample++) {
                assertThat(work.isDone()).isFalse();
                assertThat(release.getCount()).isEqualTo(1);
                MixedHttpRead read = mixedRead(owner, f.company());
                lastReadFinished = System.nanoTime();
                assertThat(assertMixedPage(read, purchase, before)).containsExactlyElementsOf(oldPreview);
                assertThat(work.isDone()).as("HTTP must finish before the writer commits or rolls back").isFalse();
                assertThat(release.getCount()).isEqualTo(1);
                Map<String, Object> backend = jdbc.queryForMap("""
                        SELECT pid,state,backend_xid::text AS transaction_id,xact_start
                        FROM pg_stat_activity WHERE pid=? AND datname=current_database()
                        """, held.backendPid());
                assertThat(backend).containsEntry("pid", held.backendPid()).containsEntry("state", "idle in transaction")
                        .containsEntry("transaction_id", held.transactionId());
                assertThat(backend.get("xact_start")).isNotNull();
                reads.add(map("sample", sample, "http", read, "writerBackend", backend,
                        "writerReleaseLatch", release.getCount(), "clientCompletedNanos", lastReadFinished, "completedBeforeRelease", true));
            }
            assertThat(mixedSource(purchase)).isEqualTo(before);
            assertThat(mixedSummary(purchase)).isEqualTo(beforeSummary);
            long releasedByReader = System.nanoTime();
            release.countDown();
            MixedWriteResult written = work.get(10, TimeUnit.SECONDS);
            assertThat(written.releaseObservedNanos()).isGreaterThanOrEqualTo(releasedByReader);
            assertThat(releasedByReader).isGreaterThan(lastReadFinished);

            MixedNoticeState after = mixedSource(purchase);
            assertThat(after).isEqualTo(held.uncommittedSource());
            assertThat(after.totalRows()).isEqualTo(100_500);
            assertThat(after.pending()).isEqualTo(100_300);
            MixedSummary afterSummary = mixedSummary(purchase);
            assertMixedSummary(afterSummary, after);
            assertThat(afterSummary.revision()).isEqualTo(beforeSummary.revision() + 2);
            MixedHttpRead committed = mixedRead(owner, f.company());
            assertMixedPage(committed, purchase, after);
            result = map("seedPending", 100_000, "seedStatements", 10, "warmups", 1,
                    "scope", "SELF", "permission", "purchase:order:list", "purchaseId", Long.toString(purchase),
                    "beforeSource", before, "beforeSummary", beforeSummary, "warmup", warmup,
                    "uncommittedWriter", held, "heldHttpReads", reads, "committedWriter", written,
                    "readPhaseReleasedNanos", releasedByReader,
                    "afterSource", after, "afterSummary", afterSummary, "afterCommitHttp", committed,
                    "writerReleaseGuardSeconds", 10, "writerFutureGuardSeconds", 10, "writerCleanupGuardSeconds", 12,
                    "timingScope", "Local integration samples: HTTP body-complete time excludes JSON assertions; DML, commit and artificial latch hold are separate. No production latency SLA.");
        }
        assertThat(noticeSummary.state()).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE doc_type='PURCHASE_ORDER' AND doc_id=?",
                Long.class, purchase)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_doc_notice_pending WHERE tenant_key=scm_notice_pending_namespace()
                    AND doc_type='PURCHASE_ORDER' AND doc_id=?
                """, Long.class, purchase)).isZero();
        result.put("cleanupRestored", true);
        evidence("notice-summary-mixed-http.json", json.valueToTree(result));
    }

    private record MixedNoticeState(long totalRows, long pending, long latestId, Instant latestTime) { }
    private record MixedSummary(long pending, long latestId, Instant latestTime, long revision, boolean initialized) { }
    private record MixedHttpRead(JsonNode page, long elapsedNanos, long requestBudgetMillis, long connectBudgetMillis) { }
    private record MixedDml(int rows, long elapsedNanos) { }
    private record MixedWriteReady(int backendPid, String transactionId, Map<String, String> databaseBudgets,
                                   MixedDml inserted, MixedDml handled, MixedNoticeState uncommittedSource,
                                   MixedSummary uncommittedSummary, long transactionStartedNanos, long holdStartedNanos) { }
    private record MixedWriteResult(long releaseObservedNanos, long artificialHoldNanos, long commitNanos,
                                    long transactionSpanNanos) { }

    private MixedHttpRead mixedRead(Account actor, long company) throws Exception {
        long started = System.nanoTime();
        var response = raw(actor, "GET", "/api/board/upstream?size=1", null, company, null);
        long elapsed = System.nanoTime() - started;
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = node(response);
        assertThat(body.path("code").intValue()).as("real summary HTTP response").isZero();
        return new MixedHttpRead(body.path("data"), elapsed, response.request().timeout().orElseThrow().toMillis(),
                http.connectTimeout().orElseThrow().toMillis());
    }

    private List<String> assertMixedPage(MixedHttpRead read, long purchase, MixedNoticeState expected) {
        JsonNode page = read.page();
        assertThat(page.path("canSeeAll").booleanValue()).isFalse();
        assertThat(page.path("total").longValue()).isEqualTo(1);
        assertThat(page.path("rows").size()).isEqualTo(1);
        assertThat(page.path("hasMore").booleanValue()).isFalse();
        JsonNode row = page.path("rows").get(0), notices = row.path("notices");
        assertThat(id(row.path("doc").path("id"))).isEqualTo(purchase);
        assertThat(row.path("noticesTotal").longValue()).isEqualTo(expected.pending());
        assertThat(notices.size()).isEqualTo(100);
        assertThat(id(notices.get(0).path("id"))).isEqualTo(expected.latestId());
        assertThat(OffsetDateTime.parse(notices.get(0).path("createTime").stringValue()).toInstant()).isEqualTo(expected.latestTime());
        List<String> ids = new ArrayList<>();
        for (JsonNode notice : notices) {
            assertThat(notice.path("handledTime").isNull()).isTrue();
            ids.add(Long.toString(id(notice.path("id"))));
        }
        assertThat(new HashSet<>(ids)).hasSize(100);
        return ids;
    }

    private MixedWriteResult mixedWriter(long purchase, long source, long owner, MixedNoticeState before,
            MixedSummary beforeSummary, CompletableFuture<MixedWriteReady> ready, CountDownLatch release) throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            long started = System.nanoTime();
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout='2s'");
                    statement.execute("SET LOCAL statement_timeout='5s'");
                }
                MixedDml inserted = mixedDml(connection, """
                        INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time)
                        SELECT 'PURCHASE_ORDER',?,'ORDER',?,'P33 mixed new notice',?,?::timestamptz+n*interval '1 second'
                        FROM generate_series(1,500) n
                        """, purchase, source, owner, before.latestTime().atOffset(ZoneOffset.UTC));
                assertThat(inserted.rows()).isEqualTo(500);
                MixedDml handled = mixedDml(connection, """
                        UPDATE biz_doc_notice SET handled_time=clock_timestamp(),handled_by=?,handle_remark='P33 mixed handled'
                        WHERE id IN (SELECT id FROM biz_doc_notice WHERE doc_type='PURCHASE_ORDER' AND doc_id=?
                            AND handled_time IS NULL AND create_time<=?::timestamptz
                            ORDER BY create_time DESC,id DESC LIMIT 200)
                        """, owner, purchase, before.latestTime().atOffset(ZoneOffset.UTC));
                assertThat(handled.rows()).isEqualTo(200);
                MixedNoticeState changed = mixedSource(connection, purchase);
                assertThat(changed.totalRows()).isEqualTo(100_500);
                assertThat(changed.pending()).isEqualTo(100_300);
                MixedSummary summary = mixedSummary(connection, purchase);
                assertMixedSummary(summary, changed);
                assertThat(summary.revision()).isEqualTo(beforeSummary.revision() + 2);
                int pid;
                String transactionId;
                Map<String, String> budgets;
                try (var statement = connection.createStatement(); var row = statement.executeQuery("""
                        SELECT pg_backend_pid(),backend_xid::text,current_setting('transaction_isolation'),
                            current_setting('lock_timeout'),current_setting('statement_timeout'),
                            current_setting('idle_in_transaction_session_timeout')
                        FROM pg_stat_activity WHERE pid=pg_backend_pid()
                        """)) {
                    assertThat(row.next()).isTrue();
                    pid = row.getInt(1); transactionId = row.getString(2);
                    assertThat(transactionId).isNotBlank();
                    budgets = Map.of("isolation", row.getString(3), "lockTimeout", row.getString(4),
                            "statementTimeout", row.getString(5), "idleTransactionTimeout", row.getString(6));
                    assertThat(budgets.get("isolation")).isEqualTo("read committed");
                }
                long holdStarted = System.nanoTime();
                ready.complete(new MixedWriteReady(pid, transactionId, budgets, inserted, handled, changed, summary, started, holdStarted));
                assertThat(release.await(10, TimeUnit.SECONDS)).as("reader must explicitly release the held transaction").isTrue();
                long releaseObserved = System.nanoTime(), commitStarted = System.nanoTime();
                connection.commit();
                long committed = System.nanoTime();
                return new MixedWriteResult(releaseObserved, releaseObserved - holdStarted, committed - commitStarted, committed - started);
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                try { connection.rollback(); }
                catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }
    }

    private static MixedDml mixedDml(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            long started = System.nanoTime();
            int rows = statement.executeUpdate();
            return new MixedDml(rows, System.nanoTime() - started);
        }
    }

    private MixedNoticeState mixedSource(long purchase) throws SQLException {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            return mixedSource(connection, purchase);
        }
    }

    private static MixedNoticeState mixedSource(Connection connection, long purchase) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT totals.total_rows,totals.pending,latest.id,latest.create_time FROM (
                    SELECT COUNT(*) AS total_rows,COUNT(*) FILTER(WHERE handled_time IS NULL) AS pending
                    FROM biz_doc_notice WHERE doc_type='PURCHASE_ORDER' AND doc_id=?) totals
                LEFT JOIN LATERAL (SELECT id,create_time FROM biz_doc_notice
                    WHERE doc_type='PURCHASE_ORDER' AND doc_id=? AND handled_time IS NULL
                    ORDER BY create_time DESC,id DESC LIMIT 1) latest ON true
                """)) {
            statement.setLong(1, purchase); statement.setLong(2, purchase);
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new MixedNoticeState(row.getLong(1), row.getLong(2), row.getLong(3), row.getObject(4, OffsetDateTime.class).toInstant());
            }
        }
    }

    private MixedSummary mixedSummary(long purchase) throws SQLException {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            return mixedSummary(connection, purchase);
        }
    }

    private static MixedSummary mixedSummary(Connection connection, long purchase) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pending_count,latest_notice_id,latest_time,revision,initialized FROM biz_doc_notice_pending
                WHERE tenant_key=scm_notice_pending_namespace() AND doc_type='PURCHASE_ORDER' AND doc_id=?
                """)) {
            statement.setLong(1, purchase);
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new MixedSummary(row.getLong(1), row.getLong(2), row.getObject(3, OffsetDateTime.class).toInstant(), row.getLong(4), row.getBoolean(5));
            }
        }
    }

    private static void assertMixedSummary(MixedSummary summary, MixedNoticeState expected) {
        assertThat(summary.initialized()).isTrue();
        assertThat(summary.pending()).isEqualTo(expected.pending());
        assertThat(summary.latestId()).isEqualTo(expected.latestId());
        assertThat(summary.latestTime()).isEqualTo(expected.latestTime());
    }

    private void cleanupMixedSummary(long purchase, NoticeSummaryMaintenance.State saved) {
        assertAll("mixed HTTP fixture cleanup",
            () -> {
                while (jdbc.update("""
                        DELETE FROM biz_doc_notice WHERE id IN (SELECT id FROM biz_doc_notice
                            WHERE doc_type='PURCHASE_ORDER' AND doc_id=? LIMIT 500)
                        """, purchase) > 0) { }
            },
            () -> cleanupSummaryFixture(purchase, saved));
    }

    private void cleanupSummaryFixture(long purchase, NoticeSummaryMaintenance.State saved) {
        // A cleanup failure is suppressed on the test failure; later cleanup still runs.
        assertAll("notice-summary HTTP fixture cleanup",
            () -> jdbc.update("DELETE FROM biz_doc_notice WHERE doc_type='PURCHASE_ORDER' AND doc_id=?", purchase),
            () -> jdbc.update("DELETE FROM biz_doc_notice_pending WHERE tenant_key=scm_notice_pending_namespace() AND doc_type='PURCHASE_ORDER' AND doc_id=?", purchase),
            () -> jdbc.update("""
                    UPDATE biz_doc_notice_pending_state SET backfill_after_type=?,backfill_after_id=?,backfill_complete=?,
                        reconcile_after_type=?,reconcile_after_id=?,reconcile_complete=?,reconcile_mismatches=?,
                        ready=?,use_summary=?,update_time=clock_timestamp() WHERE tenant_key=scm_notice_pending_namespace()
                    """, saved.backfillAfter() == null ? null : saved.backfillAfter().docType(),
                    saved.backfillAfter() == null ? null : saved.backfillAfter().docId(), saved.backfillComplete(),
                    saved.reconcileAfter() == null ? null : saved.reconcileAfter().docType(),
                    saved.reconcileAfter() == null ? null : saved.reconcileAfter().docId(), saved.reconcileComplete(),
                    saved.mismatches(), saved.ready(), saved.useSummary()));
    }

    private void finishSummaryPhase(boolean reconcile) {
        long keys = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT doc_type,doc_id FROM biz_doc_notice WHERE handled_time IS NULL
                    UNION SELECT doc_type,doc_id FROM biz_doc_notice_pending WHERE tenant_key=scm_notice_pending_namespace()) keys
                """, Long.class);
        for (long batches = keys / 500 + 2; batches > 0; batches--) {
            var progress = reconcile ? noticeSummary.reconcileBatch(500) : noticeSummary.backfillBatch(500);
            assertThat(progress.processed()).isBetween(0, 500);
            assertThat(progress.mismatches()).isZero();
            if (progress.complete()) return;
        }
        throw new AssertionError("Notice maintenance exceeded the actual key-count batch bound");
    }

    @Test void certificateCountsActualCertificatesAndSortsBeforeTwentyLimit() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "supplier:supplier:list");
        jdbc.update("UPDATE sup_supplier SET buyer_id=? WHERE id=?", owner.id(), f.supplier());
        for (int i = 0; i < 25; i++) jdbc.update("INSERT INTO sup_supplier_cert(supplier_id,cert_type,file_url,file_name,valid_until) VALUES(?,'LICENSE','/files/s2/anonymous.png',?,?)",
                f.supplier(), "S2 certificate " + i, today().minusDays(30).plusDays(i));
        Account other = custom(f.company(), "SELF", "supplier:supplier:list");
        long foreign = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,buyer_id) VALUES(?,'S2 other certificates','S2 other',?) RETURNING id",
                Long.class, "S2CERT" + suffix(), other.id());
        jdbc.update("INSERT INTO sup_supplier_cert(supplier_id,cert_type,file_url,file_name,valid_until) VALUES(?,'LICENSE','/files/s2/anonymous.png','S2 foreign certificate',?)",
                foreign, today().minusDays(365));
        JsonNode certs = group(ok(owner, "GET", "/api/board/home/todos", null, f.company()), "CERT");
        assertThat(certs.path("total").longValue()).isEqualTo(25);
        assertThat(certs.path("items").size()).isEqualTo(20);
        assertThat(certs.path("items").get(0).path("sub").stringValue()).contains(Integer.toString(today().minusDays(30).getDayOfMonth()));
        for (JsonNode item : certs.path("items")) assertThat(id(item.path("ref").path("id"))).isEqualTo(f.supplier());
        Account all = custom(f.company(), "ALL", "supplier:supplier:list");
        JsonNode allCerts = group(ok(all, "GET", "/api/board/home/todos", null, f.company()), "CERT");
        assertThat(allCerts.path("total").longValue()).isGreaterThanOrEqualTo(26);
        assertThat(id(allCerts.path("items").get(0).path("ref").path("id"))).isEqualTo(foreign);
        assertThat(group(ok(all, "GET", "/api/board/home/todos?ownerId=" + owner.id(), null, f.company()), "CERT").path("total").longValue()).isEqualTo(25);
        evidence("certificate-25-preview.json", certs);
    }

    @Test void longOrderIdentifiersRemainStringsAndErrorsRecoverOnNextRealRequest() throws Exception {
        Fixture f = fixture(); Account owner = custom(f.company(), "SELF", "order:order:list");
        long large = 9_007_199_254_740_993L;
        jdbc.update("""
                INSERT INTO ord_order(id,no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,total_amount,create_by)
                OVERRIDING SYSTEM VALUE VALUES(?,? ,?,'STOCK',CURRENT_DATE,CURRENT_DATE,?,?,'APPROVED',1,10,?)
                """, large, "S2LONG" + suffix(), f.company(), owner.id(), owner.id(), owner.id());
        JsonNode deliveries = ok(owner, "GET", "/api/board/home/deliveries", null, f.company());
        assertThat(deliveries.get(0).path("ref").path("id").stringValue()).isEqualTo(Long.toString(large));
        assertThat(call(owner, "GET", "/api/board/upstream?type=ORDER", null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(ok(owner, "GET", "/api/board/home/kpi", null, f.company()).path("order").path("count").longValue()).isEqualTo(1);
        evidence("long-id-delivery.json", deliveries);
    }

    private void notice(long purchase, long source, long owner, OffsetDateTime time) {
        jdbc.update("INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time) VALUES('PURCHASE_ORDER',?,'ORDER',?,'S2 anonymous quantity change',?,?)",
                purchase, source, owner, time);
    }
    private static JsonNode group(JsonNode todos, String key) {
        for (JsonNode group : todos.path("groups")) if (key.equals(group.path("key").stringValue())) return group;
        throw new AssertionError("Missing group " + key + " in " + todos);
    }
    private void evidence(String name, JsonNode body) throws Exception {
        Path directory = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }
}
