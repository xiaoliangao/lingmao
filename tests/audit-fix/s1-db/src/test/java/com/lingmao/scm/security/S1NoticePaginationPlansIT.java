package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.DocNoticeMapper;
import com.lingmao.scm.module.biz.mapper.DocNoticeMapper.PendingGroup;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P33: retain the real legacy group query and its two historical SQL candidates, and
 * compare them with the real synchronous-summary mapper. Count and bounded previews are
 * measured separately. All rows, triggers, ACL predicates and statement budgets remain real;
 * no planner setting is changed. Results are checked against fixture facts, not just against
 * another SQL implementation. EXPLAIN results are measurements, not a production timing SLA.
 */
class S1NoticePaginationPlansIT extends A2BusinessFixture {
    private static final String STATEMENT = "com.lingmao.scm.module.biz.mapper.DocNoticeMapper.selectPendingGroups";
    private static final List<BizType> TYPES = List.of(BizType.MRP_CALC, BizType.PRODUCTION_ORDER, BizType.PURCHASE_ORDER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-01-15T08:00:00Z");
    private static final Path ARTIFACTS = Path.of("test-artifacts/full-audit/s1/notice-pagination");
    private static final Comparator<PendingGroup> ASC = Comparator.comparing((PendingGroup g) -> g.latest().toInstant())
            .thenComparing(g -> g.docType().name()).thenComparing(PendingGroup::docId);

    @Autowired DocNoticeMapper notices;
    @Autowired SqlSessionFactory sessions;
    @Autowired TransactionTemplate transactions;
    @Autowired DeploymentNamespace namespace;

    @Test void uniformFiveThousandGroupsAndHandledHistory() throws Exception {
        scenario("uniform-5001", data -> {
            var ordinary = heads(data, BizType.PURCHASE_ORDER, 5000, data.self.id(), data.self.id());
            var hot = heads(data, BizType.PURCHASE_ORDER, 1, data.self.id(), data.self.id());
            addNotices(data, ordinary, 20, BASE_TIME, 0, 1, false, data.other.id());
            addNotices(data, hot, 125, BASE_TIME.plusDays(2), 0, 1, false, data.other.id());
            addNotices(data, hot, 20_000, BASE_TIME.minusDays(2), 0, 1, true, data.self.id());
            analyze();
            assertThat(data.pending).isEqualTo(100_125);
            assertThat(data.handled).isEqualTo(20_000);
            probe(data, "first", query(data.self, null, null, false, 51, TYPES));
            probe(data, "home-oldest", query(data.self, null, null, true, 21, TYPES));
        });
    }

    @Test void oneDocumentWithOneHundredThousandPendingNotices() throws Exception {
        scenario("single-hot-100000", data -> {
            var hot = heads(data, BizType.PURCHASE_ORDER, 1, data.self.id(), data.self.id());
            var other = heads(data, BizType.PURCHASE_ORDER, 120, data.self.id(), data.self.id());
            addNotices(data, hot, 100_000, BASE_TIME.plusDays(3), 0, 2, false, data.self.id());
            addNotices(data, other, 3, BASE_TIME, 1, 86_400, false, data.self.id());
            analyze();
            Query first = query(data.self, null, null, false, 51, TYPES);
            probe(data, "first-including-hot-count", first);
            var ordered = expected(data, first);
            assertThat(ordered.getFirst().noticesTotal()).isEqualTo(100_000);
            probe(data, "after-hot", first.after(ordered.getFirst()));
            probe(data, "oldest", query(data.self, null, null, true, 21, TYPES));
        });
    }

    @Test void sparseSelfScopeAndCurrentOwnershipOverrideStaleNoticeOwner() throws Exception {
        scenario("sparse-self", data -> {
            var hidden = heads(data, BizType.PURCHASE_ORDER, 5000, data.other.id(), data.other.id());
            addNotices(data, hidden, 20, BASE_TIME.plusDays(3), 1, 2, false, data.self.id());
            var byOwner = heads(data, BizType.PURCHASE_ORDER, 2, data.other.id(), data.self.id());
            var byCreator = heads(data, BizType.PURCHASE_ORDER, 2, data.self.id(), data.other.id());
            var production = heads(data, BizType.PRODUCTION_ORDER, 2, data.other.id(), data.self.id());
            var mrp = heads(data, BizType.MRP_CALC, 2, data.self.id(), data.self.id());
            for (var visible : List.of(byOwner, byCreator, production, mrp)) {
                addNotices(data, visible, 3, BASE_TIME, 1, 2, false, data.other.id());
            }
            analyze();
            Query self = query(data.self, null, null, false, 51, TYPES);
            assertThat(expected(data, self)).hasSize(8);
            probe(data, "self-all-modules", self);
            Query owned = query(data.self, data.self.id(), null, false, 51, TYPES);
            assertThat(expected(data, owned)).hasSize(6);
            probe(data, "self-owner-filter", owned);
            Query purchaseOnly = query(data.self, null, null, false, 51, List.of(BizType.PURCHASE_ORDER));
            assertThat(expected(data, purchaseOnly)).hasSize(4);
            probe(data, "self-module-filter", purchaseOnly);
            Query allOwner = query(data.all, data.other.id(), null, false, 51, TYPES);
            assertThat(expected(data, allOwner)).hasSize(5002);
            probe(data, "all-current-owner", allOwner);
            Query allWithoutOwner = query(data.all, null, null, false, 51, TYPES);
            assertThat(expected(data, allWithoutOwner)).hasSize(5008);
            isolatedAllProbe(data, allWithoutOwner);
            probe(data, "no-modules", query(data.self, null, null, false, 51, List.of()));
            probe(data, "anonymous", query(null, null, null, false, 51, TYPES));
        });
    }

    @Test void oneHundredThousandVisibleHeadersWithoutNotices() throws Exception {
        scenario("empty-headers-100000", data -> {
            heads(data, BizType.PURCHASE_ORDER, 100_000, data.self.id(), data.self.id());
            var live = heads(data, BizType.PURCHASE_ORDER, 25, data.self.id(), data.self.id());
            addNotices(data, live, 20, BASE_TIME, 1, 2, false, data.self.id());
            analyze();
            assertThat(data.headers).isEqualTo(100_025);
            assertThat(data.pending).isEqualTo(500);
            probe(data, "first", query(data.self, null, null, false, 51, TYPES));
            probe(data, "oldest", query(data.self, null, null, true, 21, TYPES));
        });
    }

    @Test void identicalTimestampsAcrossAllTypesHaveNoMissingOrDuplicateGroups() throws Exception {
        scenario("equal-timestamps", data -> {
            for (BizType type : TYPES) {
                var docs = heads(data, type, 64, data.self.id(), data.self.id());
                addNotices(data, docs, 21, BASE_TIME, 0, 0, false, data.other.id());
                addNotices(data, docs, 5, BASE_TIME.plusDays(1), 0, 0, true, data.self.id());
            }
            analyze();
            Query newest = query(data.self, null, null, false, 18, TYPES);
            Query oldest = query(data.self, null, null, true, 18, TYPES);
            probe(data, "newest", newest);
            probe(data, "oldest", oldest);
            walkEveryPage(data, newest, 17);
            walkEveryPage(data, oldest, 17);
        });
    }

    @Test void deepLastAndEmptyPagesRejectOlderNoticesOfEarlierGroups() throws Exception {
        scenario("deep-tail-5001", data -> {
            var docs = heads(data, BizType.PURCHASE_ORDER, 5001, data.self.id(), data.self.id());
            // Each earlier group also has notices older than the tail cursor. Pushing the
            // cursor into the MAX/latest lookup would incorrectly repeat those groups.
            addNotices(data, docs, 20, BASE_TIME, 1, 86_400, false, data.self.id());
            analyze();
            for (boolean oldest : List.of(false, true)) {
                Query whole = query(data.self, null, null, oldest, 51, TYPES);
                List<PendingGroup> all = expected(data, whole);
                assertThat(all).hasSize(5001);
                Query tail = whole.after(all.get(all.size() - 18));
                assertThat(expectedPage(data, tail)).hasSize(17);
                probe(data, oldest ? "oldest-tail" : "newest-tail", tail);
                Query empty = whole.after(all.getLast());
                assertThat(expectedPage(data, empty)).isEmpty();
                probe(data, oldest ? "oldest-empty" : "newest-empty", empty);
            }
        });
    }

    private void scenario(String name, Consumer<Dataset> run) throws Exception {
        Fixture fixture = fixture();
        Account self = custom(fixture.company(), "SELF", "purchase:order:list", "production:order:list", "purchase:mrp:list");
        Account other = custom(fixture.company(), "SELF", "purchase:order:list");
        Account all = custom(fixture.company(), "ALL", "purchase:order:list", "production:order:list", "purchase:mrp:list");
        long order = sourceOrder(fixture, other, "APPROVED", fixture.company());
        Dataset data = new Dataset(name, fixture, self, other, all, order);
        System.out.println("P33 scenario start: " + name);
        try (SqlCleanup fixtureCleanup = () -> {
            SecurityContextHolder.clearContext();
            analyze();
        }) {
            // This is a read-model benchmark, not a transaction/concurrency simulation.
            // Real tables, constraints and audit triggers remain enabled; large anonymous
            // fixtures and their audit events are removed by rollback, never by a bypass.
            transactions.executeWithoutResult(status -> {
                status.setRollbackOnly();
                run.accept(data);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE source_type='ORDER' "
                        + "AND source_id=? AND content='P33 synthetic notification' AND handled_time IS NULL",
                        Long.class, data.sourceOrder)).isEqualTo(data.pending);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE source_type='ORDER' "
                        + "AND source_id=? AND content='P33 synthetic notification' AND handled_time IS NOT NULL",
                        Long.class, data.sourceOrder)).isEqualTo(data.handled);
                write(name + "/fixture.json", Map.of("headers", data.headers, "pending", data.pending,
                        "handled", data.handled, "groups", data.facts.size(), "status", "semantic checks passed"));
                write(name + "/maintenance.json", Map.of("insertStatements", data.insertSamples.size(),
                        "samples", data.insertSamples,
                        "measurementLimit", "Each raw plan executed the actual fixture INSERT once with EXPLAIN ANALYZE. "
                                + "Wall time includes instrumentation and all active triggers; node WAL is reported as emitted "
                                + "by PostgreSQL, not an isolated summary-maintenance cost or a production SLA. "
                                + "Revision changes count logical per-key updates; first initialization also inserts a lock row."));
            });
        }
        System.out.println("P33 scenario completed and fixture rolled back: " + name);
    }

    private List<Document> heads(Dataset d, BizType type, int count, long creator, long owner) {
        List<Document> result = new ArrayList<>(count);
        String prefix = "P33" + suffix();
        for (int start = 1; start <= count; start += 1000) {
            int end = Math.min(start + 999, count);
            List<Long> ids = switch (type) {
                case PURCHASE_ORDER -> jdbc.queryForList("""
                        INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,
                                              warehouse_id,settle_method,tax_pct,status,total_qty,total_amount,create_by)
                        SELECT ? || n,?,?,'STOCK',?,?,?,?,'MONTHLY',13,'APPROVED',0,0,?
                        FROM generate_series(?,?) n RETURNING id
                        """, Long.class, prefix, d.fixture.company(), d.fixture.supplier(), owner, today(), today().plusDays(30),
                        d.fixture.warehouse(), creator, start, end);
                case PRODUCTION_ORDER -> jdbc.queryForList("""
                        INSERT INTO prd_order(no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,
                                              order_date,due_date,unit_price,tax_pct,total_qty,total_amount,merch_id,
                                              settle_method,status,issue_status,create_by)
                        SELECT ? || n,?,'OUTSOURCE',?,'CMT',?,?,?,?,?,1,13,0,0,?,'MONTHLY','APPROVED','NONE',?
                        FROM generate_series(?,?) n RETURNING id
                        """, Long.class, prefix, d.fixture.company(), d.fixture.supplier(), d.sourceOrder, d.fixture.style(),
                        d.fixture.pack(), today(), today().plusDays(30), owner, creator, start, end);
                case MRP_CALC -> jdbc.queryForList("""
                        INSERT INTO mrp_calc(no,status,create_by)
                        SELECT ? || n,'CONFIRMED',? FROM generate_series(?,?) n RETURNING id
                        """, Long.class, prefix, creator, start, end);
                default -> throw new IllegalArgumentException("Unsupported benchmark type");
            };
            long currentOwner = type == BizType.MRP_CALC ? creator : owner;
            ids.forEach(id -> result.add(new Document(type, id, creator, currentOwner)));
            d.headers += ids.size();
            if (count >= 100_000 && end % 10_000 == 0) System.out.println("P33 " + d.name + " headers=" + end);
        }
        return result;
    }

    private void addNotices(Dataset d, List<Document> docs, int perDoc, OffsetDateTime time, int groupStep,
                            int noticeStep, boolean handled, long snapshotOwner) {
        if (docs.isEmpty()) return;
        for (int docStart = 0; docStart < docs.size(); docStart += 500) {
            List<Document> batch = docs.subList(docStart, Math.min(docStart + 500, docs.size()));
            OffsetDateTime batchTime = time.plusSeconds((long) docStart * groupStep);
            for (int eventStart = 0; eventStart < perDoc; eventStart += 10_000) {
                int firstEvent = eventStart, lastEvent = Math.min(eventStart + 9999, perDoc - 1);
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    var ids = connection.createArrayOf("bigint", batch.stream().map(Document::id).toArray());
                    String planPath = d.name + "/write-plans/insert-" + d.insertSamples.size() + ".json";
                    try (var statement = connection.prepareStatement("""
                            EXPLAIN (ANALYZE, BUFFERS, WAL, VERBOSE, FORMAT JSON)
                            INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time,handled_time)
                            SELECT ?,doc.id,'ORDER',?,'P33 synthetic notification',?,
                                   CAST(? AS timestamptz) + (doc.seq - 1) * ? * interval '1 second'
                                     - event.seq * ? * interval '1 second',
                                   CASE WHEN ? THEN clock_timestamp() ELSE NULL END
                            FROM unnest(CAST(? AS bigint[])) WITH ORDINALITY doc(id,seq)
                            CROSS JOIN generate_series(?,?) event(seq)
                            """)) {
                        Map<Long, Long> before = revisions(connection, ids, docs.getFirst().type());
                        statement.setString(1, docs.getFirst().type().name()); statement.setLong(2, d.sourceOrder);
                        statement.setLong(3, snapshotOwner); statement.setObject(4, batchTime);
                        statement.setInt(5, groupStep); statement.setInt(6, noticeStep); statement.setBoolean(7, handled);
                        statement.setArray(8, ids); statement.setInt(9, firstEvent); statement.setInt(10, lastEvent);
                        long started = System.nanoTime();
                        String plan;
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue(); plan = rows.getString(1); assertThat(rows.next()).isFalse();
                        }
                        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
                        writeText(planPath, plan);
                        Map<Long, Long> after = revisions(connection, ids, docs.getFirst().type());
                        if (handled) assertThat(after).as("handled history must not create or change summary rows").isEqualTo(before);
                        else assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(batch.stream().map(Document::id).toList());
                        List<Map<String, Object>> keyRevisions = new ArrayList<>(batch.size());
                        for (Document document : batch) {
                            long previous = before.getOrDefault(document.id(), 0L);
                            long current = after.getOrDefault(document.id(), 0L);
                            assertThat(current - previous).as("revision delta for actual document key %s", document.id())
                                    .isEqualTo(handled ? 0 : 1);
                            keyRevisions.add(Map.of("docId", Long.toString(document.id()),
                                    "before", previous, "after", current, "delta", current - previous));
                        }
                        long revisionBefore = before.values().stream().mapToLong(Long::longValue).sum();
                        long revisionAfter = after.values().stream().mapToLong(Long::longValue).sum();
                        long delta = revisionAfter - revisionBefore;
                        // Every real pending key changes once per statement, including first initialization.
                        // Inserting handled history must not touch the pending summary at all.
                        assertThat(delta).as("one logical revision per affected key, not per notice")
                                .isEqualTo(handled ? 0 : batch.size());
                        d.insertSamples.add(Map.of("planPath", planPath,
                                "insertedRows", (long) batch.size() * (lastEvent - firstEvent + 1),
                                "documentKeys", batch.size(), "handled", handled,
                                "revisionBefore", revisionBefore, "revisionAfter", revisionAfter,
                                "revisionDelta", delta, "revisions", keyRevisions, "statementWallMs", elapsedMs));
                    } finally { ids.free(); }
                    return null;
                });
            }
        }
        long added = (long) docs.size() * perDoc;
        if (handled) { d.handled += added; return; }
        d.pending += added;
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i); OffsetDateTime latest = time.plusSeconds((long) i * groupStep);
            Fact before = d.facts.get(doc);
            if (before != null && before.latest().isAfter(latest)) latest = before.latest();
            d.facts.put(doc, new Fact(latest, perDoc + (before == null ? 0 : before.count())));
        }
    }

    private Map<Long, Long> revisions(Connection connection, java.sql.Array ids, BizType type) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT doc_id,revision "
                + "FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=ANY(CAST(? AS bigint[])) ORDER BY doc_id")) {
            statement.setString(1, namespace.tenant()); statement.setString(2, type.name()); statement.setArray(3, ids);
            try (var rows = statement.executeQuery()) {
                Map<Long, Long> result = new LinkedHashMap<>();
                while (rows.next()) assertThat(result.put(rows.getLong("doc_id"), rows.getLong("revision"))).isNull();
                return result;
            }
        }
    }

    private Query query(Account actor, Long owner, Cursor cursor, boolean oldest, int limit, List<BizType> types) {
        if (actor == null) SecurityContextHolder.clearContext(); else authenticate(actor);
        return new Query(DataScope.current(false), types, owner, cursor, oldest, limit);
    }

    private void analyze() {
        for (String table : List.of("pur_order", "prd_order", "mrp_calc", "biz_doc_notice", "biz_doc_notice_pending")) {
            jdbc.execute("ANALYZE " + table);
        }
    }

    private List<PendingGroup> expected(Dataset data, Query q) {
        List<PendingGroup> result = new ArrayList<>();
        for (var entry : data.facts.entrySet()) {
            Document doc = entry.getKey(); Fact fact = entry.getValue();
            if (!q.types().contains(doc.type())) continue;
            if (q.owner() != null && q.owner() != doc.owner()) continue;
            if (q.scope().isSelf() && !Objects.equals(q.scope().getUserId(), doc.creator())
                    && !Objects.equals(q.scope().getUserId(), doc.owner())) continue;
            result.add(new PendingGroup(doc.type(), doc.id(), fact.latest(), fact.count()));
        }
        result.sort(q.oldest() ? ASC : ASC.reversed());
        return result;
    }

    private List<PendingGroup> expectedPage(Dataset data, Query q) {
        return expected(data, q).stream().filter(g -> q.cursor() == null
                        || (q.oldest() ? ASC.compare(g, q.cursor().group()) > 0 : ASC.compare(g, q.cursor().group()) < 0))
                .limit(Math.max(1, Math.min(q.limit(), 101))).toList();
    }

    private void isolatedAllProbe(Dataset data, Query q) {
        assertThat(q.scope().isAll()).isTrue();
        assertThat(q.owner()).isNull();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getAutoCommit()).as("ALL probe uses the existing rollback-only fixture transaction").isFalse();
            // ALL has no fixture/user filter in production. Other integration tests may have
            // left real pending rows, so isolate only this extra probe with ordinary DELETE.
            // These full-row temporary snapshots are created before the savepoint; rollback
            // must restore both source rows and every affected summary tuple, including its
            // revision/initialized flag and absence of any newly created zero-count anchor.
            String outside = "handled_time IS NULL AND doc_type IN ('MRP_CALC','PRODUCTION_ORDER','PURCHASE_ORDER') "
                    + "AND (source_type,source_id) IS DISTINCT FROM ('ORDER',?)";
            jdbc.execute("CREATE TEMP TABLE p33_all_notice_before ON COMMIT DROP AS SELECT * FROM biz_doc_notice WHERE false");
            long outsideRows = jdbc.update("INSERT INTO pg_temp.p33_all_notice_before SELECT * FROM biz_doc_notice WHERE "
                    + outside, data.sourceOrder);
            String affectedSummaries = "SELECT g.* FROM biz_doc_notice_pending g WHERE EXISTS "
                    + "(SELECT 1 FROM pg_temp.p33_all_notice_before n WHERE n.doc_type=g.doc_type AND n.doc_id=g.doc_id)";
            jdbc.execute("CREATE TEMP TABLE p33_all_summary_before ON COMMIT DROP AS " + affectedSummaries);
            long summaryRows = jdbc.queryForObject("SELECT COUNT(*) FROM pg_temp.p33_all_summary_before", Long.class);
            var savepoint = connection.setSavepoint();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("method", "savepoint-normal-delete");
            evidence.put("scope", "ALL");
            evidence.put("ownerFilter", false);
            evidence.put("outsidePendingBefore", outsideRows);
            evidence.put("affectedSummaryRowsBefore", summaryRows);
            evidence.put("probeCompleted", false);
            // try-with-resources preserves a query/assertion failure as primary if rollback,
            // restoration checks or evidence writing also fail. The outer transaction remains
            // rollback-only on every path; no source or summary rows are committed here.
            try (SqlCleanup restoration = () -> {
                connection.rollback(savepoint);
                long noticeDifferences = jdbc.queryForObject("SELECT COUNT(*) FROM ((TABLE pg_temp.p33_all_notice_before "
                        + "EXCEPT ALL SELECT * FROM biz_doc_notice WHERE " + outside + ") UNION ALL "
                        + "(SELECT * FROM biz_doc_notice WHERE " + outside
                        + " EXCEPT ALL TABLE pg_temp.p33_all_notice_before)) differences", Long.class,
                        data.sourceOrder, data.sourceOrder);
                long summaryDifferences = jdbc.queryForObject("SELECT COUNT(*) FROM ((TABLE pg_temp.p33_all_summary_before "
                        + "EXCEPT ALL " + affectedSummaries + ") UNION ALL (" + affectedSummaries
                        + " EXCEPT ALL TABLE pg_temp.p33_all_summary_before)) differences", Long.class);
                evidence.put("restoredNoticeDifferences", noticeDifferences);
                evidence.put("restoredSummaryDifferences", summaryDifferences);
                write(data.name + "/all-no-owner-isolation.json", evidence);
                assertThat(noticeDifferences).as("full source rows restored after ALL probe").isZero();
                assertThat(summaryDifferences).as("all affected summary tuples restored after ALL probe").isZero();
                connection.releaseSavepoint(savepoint);
                jdbc.execute("DROP TABLE pg_temp.p33_all_notice_before, pg_temp.p33_all_summary_before");
            }) {
                int removed = jdbc.update("DELETE FROM biz_doc_notice n USING pg_temp.p33_all_notice_before saved WHERE n.id=saved.id");
                evidence.put("outsidePendingDeleted", removed);
                assertThat((long) removed).isEqualTo(outsideRows);
                long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE " + outside,
                        Long.class, data.sourceOrder);
                evidence.put("outsidePendingDuringProbe", remaining);
                assertThat(remaining).isZero();
                long fixturePending = jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE source_type='ORDER' "
                        + "AND source_id=? AND handled_time IS NULL", Long.class, data.sourceOrder);
                evidence.put("fixturePendingDuringProbe", fixturePending);
                assertThat(fixturePending).isEqualTo(data.pending);
                probe(data, "all-no-owner", q);
                evidence.put("probeCompleted", true);
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface SqlCleanup extends AutoCloseable {
        @Override void close() throws SQLException;
    }

    private void probe(Dataset data, String label, Query q) {
        List<PendingGroup> expected = expectedPage(data, q);
        long expectedTotal = expected(data, q).size();
        assertThat(notices.countPendingGroups(q.types(), q.owner(), q.scope())).isEqualTo(expectedTotal);
        assertThat(production(q)).as(data.name + " / " + label + " actual mapper").containsExactlyElementsOf(expected);
        Map<String, BoundSql> candidates = candidates(q);
        for (var entry : candidates.entrySet()) {
            assertThat(execute(entry.getValue())).as(entry.getKey()).containsExactlyElementsOf(expected);
            writeText(data.name + "/" + label + "/" + entry.getKey() + ".sql", entry.getValue().getSql());
        }
        // The page query includes one lookahead group. Production trims it before loading previews.
        int pageSize = q.limit() - 1;
        List<PendingGroup> previewGroups = expected.subList(0, Math.min(pageSize, expected.size()));
        Map<String, BoundSql> auxiliary = auxiliary(q, previewGroups);
        assertThat(scalar(auxiliary.get("legacy-total"))).isEqualTo(expectedTotal);
        assertThat(scalar(auxiliary.get("summary-total"))).isEqualTo(expectedTotal);
        int previewLimit = q.oldest() ? 1 : 100;
        long expectedPreviews = previewGroups.stream().mapToLong(g -> Math.min(g.noticesTotal(), previewLimit)).sum();
        var previews = notices.selectPendingPreviews(previewGroups, q.owner(), q.scope(), previewLimit);
        assertThat(previews).hasSize(Math.toIntExact(expectedPreviews));
        assertThat(new HashSet<>(previews.stream().map(n -> n.getId()).toList())).hasSize(previews.size());
        for (var notice : previews) {
            assertThat(notice.getHandledTime()).isNull();
            assertThat(previewGroups.stream().anyMatch(g -> g.docType() == notice.getDocType()
                    && g.docId().equals(notice.getDocId()))).isTrue();
        }
        Map<String, Long> auxiliaryRows = Map.of("legacy-total", 1L, "summary-total", 1L,
                "bounded-previews", expectedPreviews);
        for (var entry : auxiliary.entrySet()) {
            writeText(data.name + "/" + label + "/" + entry.getKey() + ".sql", entry.getValue().getSql());
        }
        Map<String, List<Map<String, Object>>> samples = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> auxiliarySamples = new LinkedHashMap<>();
        Map<String, BoundSql> allQueries = new LinkedHashMap<>(candidates);
        allQueries.putAll(auxiliary);
        List<String> names = new ArrayList<>(allQueries.keySet());
        // Rotate candidate order after semantic checks have warmed every query; retain all samples instead of a
        // single favorable timing. No forced index, planner GUC, VACUUM or disabled trigger.
        for (int round = 0; round < 3; round++) {
            for (int slot = 0; slot < names.size(); slot++) {
                String name = names.get((round + slot) % names.size());
                String plan = explain(allQueries.get(name));
                writeText(data.name + "/" + label + "/" + name + "-" + round + ".json", plan);
                long rows = auxiliaryRows.getOrDefault(name, (long) expected.size());
                assertThat(json.readTree(plan).get(0).path("Plan").path("Actual Rows").longValue()).isEqualTo(rows);
                var destination = auxiliary.containsKey(name) ? auxiliarySamples : samples;
                destination.computeIfAbsent(name, ignored -> new ArrayList<>()).add(metrics(plan));
            }
        }
        write(data.name + "/" + label + "/summary.json", Map.ofEntries(
                Map.entry("resultCount", expected.size()), Map.entry("oldest", q.oldest()),
                Map.entry("cursor", q.cursor() == null ? "first" : q.cursor().toString()),
                Map.entry("measurementVersion", 2), Map.entry("samples", samples), Map.entry("totalGroups", expectedTotal),
                Map.entry("pageSize", pageSize), Map.entry("previewGroups", previewGroups.size()),
                Map.entry("previewRows", expectedPreviews), Map.entry("auxiliaryRows", auxiliaryRows),
                Map.entry("auxiliarySamples", auxiliarySamples)));
        System.out.println("P33 compared " + data.name + " / " + label + ": " + samples);
    }

    private void walkEveryPage(Dataset data, Query initial, int pageSize) {
        Query q = initial; List<PendingGroup> seen = new ArrayList<>();
        List<PendingGroup> all = expected(data, initial); int pages = 0;
        while (true) {
            List<PendingGroup> wanted = expectedPage(data, q);
            assertThat(production(q)).containsExactlyElementsOf(wanted);
            for (BoundSql candidate : candidates(q).values()) assertThat(execute(candidate)).containsExactlyElementsOf(wanted);
            int used = Math.min(pageSize, wanted.size());
            seen.addAll(wanted.subList(0, used));
            if (wanted.size() <= pageSize) break;
            q = q.after(wanted.get(used - 1));
            assertThat(++pages).isLessThan(all.size() / pageSize + 3);
        }
        assertThat(seen).containsExactlyElementsOf(all);
        assertThat(new HashSet<>(seen.stream().map(g -> g.docType().name() + ":" + g.docId()).toList())).hasSize(all.size());
        write(data.name + "/walk-" + (initial.oldest() ? "oldest" : "newest") + ".json",
                Map.of("groups", seen.size(), "pages", pages + 1, "duplicates", 0, "missing", 0));
    }

    private List<PendingGroup> production(Query q) {
        return notices.selectPendingGroups(q.types(), q.owner(), q.scope(), q.cursor() == null ? null : q.cursor().time(),
                q.cursor() == null ? null : q.cursor().type(), q.cursor() == null ? null : q.cursor().id(), q.oldest(), q.limit());
    }

    private Map<String, BoundSql> candidates(Query q) {
        Map<String, Object> args = parameters(q);
        BoundSql original = mapped().getBoundSql(args);
        String sql = original.getSql().replaceAll("\\s+", " ").strip();
        String grouped = replaceOnce(sql, "MAX(n.create_time) AS latest, COUNT(*) AS notices_total", "MAX(n.create_time) AS latest");
        grouped = replaceOnce(grouped, "SELECT g.doc_type, g.doc_id, g.latest, g.notices_total", "SELECT g.doc_type, g.doc_id, g.latest");
        String visible = between(grouped, "JOIN (", ") visible ON");
        String types = between(grouped, "WHERE n.handled_time IS NULL", "GROUP BY n.doc_type, n.doc_id")
                .replace("n.doc_type", "visible.doc_type");
        int suffixAt = grouped.lastIndexOf(") g");
        assertThat(suffixAt).as("known production grouped query boundary").isPositive();
        String suffix = grouped.substring(suffixAt + 3);
        String lateral = "SELECT g.doc_type, g.doc_id, g.latest FROM ("
                + "SELECT visible.doc_type, visible.doc_id, latest.create_time AS latest FROM (" + visible + ") visible "
                + "JOIN LATERAL (SELECT dn.create_time FROM biz_doc_notice dn WHERE dn.doc_type=visible.doc_type "
                + "AND dn.doc_id=visible.doc_id AND dn.handled_time IS NULL "
                + "ORDER BY dn.create_time DESC,dn.id DESC LIMIT 1) latest ON true WHERE true " + types + ") g" + suffix;
        Map<String, BoundSql> result = new LinkedHashMap<>();
        result.put("production-group-count", original);
        result.put("group-count-after-page", copy(original, withPageCount(grouped, q.oldest())));
        result.put("visible-head-lateral", copy(original, withPageCount(lateral, q.oldest())));
        result.put("synchronous-summary", mapped("selectSummaryPendingGroups").getBoundSql(args));
        return result;
    }

    private Map<String, Object> parameters(Query q) {
        Map<String, Object> args = new HashMap<>();
        args.put("types", q.types()); args.put("ownerId", q.owner()); args.put("scope", q.scope());
        args.put("cursorTime", q.cursor() == null ? null : q.cursor().time());
        args.put("cursorType", q.cursor() == null ? null : q.cursor().type());
        args.put("cursorId", q.cursor() == null ? null : q.cursor().id());
        args.put("oldest", q.oldest()); args.put("limit", q.limit()); args.put("tenantKey", namespace.tenant());
        return args;
    }

    private Map<String, BoundSql> auxiliary(Query q, List<PendingGroup> page) {
        Map<String, Object> args = parameters(q);
        Map<String, BoundSql> result = new LinkedHashMap<>();
        result.put("legacy-total", mapped("countPendingGroups").getBoundSql(args));
        result.put("summary-total", mapped("countSummaryPendingGroups").getBoundSql(args));
        Map<String, Object> previewArgs = new HashMap<>(args);
        previewArgs.put("groups", page); previewArgs.put("limit", q.oldest() ? 1 : 100);
        result.put("bounded-previews", mapped("selectPendingPreviews").getBoundSql(previewArgs));
        return result;
    }

    private static String withPageCount(String page, boolean oldest) {
        String direction = oldest ? "" : " DESC";
        return "SELECT page.doc_type,page.doc_id,page.latest,(SELECT COUNT(*) FROM biz_doc_notice dn "
                + "WHERE dn.doc_type=page.doc_type AND dn.doc_id=page.doc_id AND dn.handled_time IS NULL) AS notices_total "
                + "FROM (" + page + ") page ORDER BY page.latest" + direction + ",page.doc_type" + direction + ",page.doc_id" + direction;
    }

    private static String replaceOnce(String input, String from, String to) {
        int first = input.indexOf(from);
        assertThat(first).as("candidate matches current production SQL: %s", from).isNotNegative();
        assertThat(input.indexOf(from, first + from.length())).isNegative();
        return input.substring(0, first) + to + input.substring(first + from.length());
    }

    private static String between(String input, String start, String end) {
        int from = input.indexOf(start), to = input.indexOf(end, from + start.length());
        assertThat(from).isNotNegative(); assertThat(to).isGreaterThan(from);
        return input.substring(from + start.length(), to);
    }

    private BoundSql copy(BoundSql original, String sql) {
        BoundSql result = new BoundSql(sessions.getConfiguration(), sql, original.getParameterMappings(), original.getParameterObject());
        original.getAdditionalParameters().forEach(result::setAdditionalParameter);
        return result;
    }

    private MappedStatement mapped() { return sessions.getConfiguration().getMappedStatement(STATEMENT); }

    private MappedStatement mapped(String method) {
        return sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.biz.mapper.DocNoticeMapper." + method);
    }

    private long scalar(BoundSql sql) {
        return jdbc.execute((ConnectionCallback<Long>) connection -> {
            try (var statement = connection.prepareStatement(sql.getSql())) {
                new DefaultParameterHandler(mapped(), sql.getParameterObject(), sql).setParameters(statement);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    long result = rows.getLong(1);
                    assertThat(rows.wasNull()).isFalse();
                    assertThat(rows.next()).isFalse();
                    return result;
                }
            }
        });
    }

    private List<PendingGroup> execute(BoundSql sql) {
        return jdbc.execute((ConnectionCallback<List<PendingGroup>>) connection -> {
            try (var statement = connection.prepareStatement(sql.getSql())) {
                new DefaultParameterHandler(mapped(), sql.getParameterObject(), sql).setParameters(statement);
                try (var rows = statement.executeQuery()) {
                    List<PendingGroup> result = new ArrayList<>();
                    while (rows.next()) result.add(group(rows));
                    return result;
                }
            }
        });
    }

    private static PendingGroup group(ResultSet row) throws SQLException {
        return new PendingGroup(BizType.valueOf(row.getString("doc_type")), row.getLong("doc_id"),
                row.getObject("latest", OffsetDateTime.class), row.getLong("notices_total"));
    }

    private String explain(BoundSql sql) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                new DefaultParameterHandler(mapped(), sql.getParameterObject(), sql).setParameters(statement);
                try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
            }
        });
    }

    private Map<String, Object> metrics(String plan) {
        JsonNode parsed = json.readTree(plan).get(0), root = parsed.path("Plan");
        Map<String, Double> scanRows = new LinkedHashMap<>();
        collectScans(root, scanRows);
        return Map.of("executionMs", number(parsed.path("Execution Time")),
                "sharedHits", number(root.path("Shared Hit Blocks")), "sharedReads", number(root.path("Shared Read Blocks")),
                "tempWrites", number(root.path("Temp Written Blocks")), "scanRowsIncludingFiltered", scanRows);
    }

    private static void collectScans(JsonNode node, Map<String, Double> result) {
        if (node.has("Relation Name")) {
            double scanned = (number(node.path("Actual Rows")) + number(node.path("Rows Removed by Filter"))
                    + number(node.path("Rows Removed by Index Recheck"))) * number(node.path("Actual Loops"));
            result.merge(node.path("Relation Name").stringValue(), scanned, Double::sum);
        }
        if (node.has("Plans")) for (JsonNode child : node.path("Plans")) collectScans(child, result);
    }

    private static double number(JsonNode node) { return node.isNumber() ? node.doubleValue() : 0; }

    private void write(String relative, Object value) { writeText(relative, json.writeValueAsString(value)); }

    private void writeText(String relative, String value) {
        Path target = ARTIFACTS.resolve(relative);
        try { Files.createDirectories(target.getParent()); Files.writeString(target, value); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private record Document(BizType type, long id, long creator, long owner) { }
    private record Fact(OffsetDateTime latest, long count) { }
    private record Cursor(OffsetDateTime time, BizType type, Long id) {
        PendingGroup group() { return new PendingGroup(type, id, time, 0); }
    }
    private record Query(DataScope scope, List<BizType> types, Long owner, Cursor cursor, boolean oldest, int limit) {
        Query after(PendingGroup last) { return new Query(scope, types, owner,
                new Cursor(last.latest(), last.docType(), last.docId()), oldest, limit); }
    }
    private static final class Dataset {
        final String name;
        final Fixture fixture;
        final Account self;
        final Account other;
        final Account all;
        final long sourceOrder;
        final Map<Document, Fact> facts = new LinkedHashMap<>();
        final List<Map<String, Object>> insertSamples = new ArrayList<>();
        long headers;
        long pending;
        long handled;

        Dataset(String name, Fixture fixture, Account self, Account other, Account all, long sourceOrder) {
            this.name = name; this.fixture = fixture; this.self = self; this.other = other; this.all = all; this.sourceOrder = sourceOrder;
        }
    }
}
