package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * P33：只调用真实来源 DML、业务处理及维护入口。锁交错由服务器的 blocker PID 证明，
 * 不用 sleep 推断并发；维护夹具先提交，避免 REQUIRES_NEW 等待测试自己的外层事务。
 */
@IntegrationTest
class NoticeSummaryMaintenanceIT {
    private static final String TYPE = "PRODUCTION_ORDER";
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NoticeSummaryMaintenance maintenance;
    @Autowired DeploymentNamespace namespace;
    @Autowired DocNoticeService notices;
    @Autowired JsonMapper json;

    private final long fixture = ThreadLocalRandom.current().nextLong(8_000_000_000_000L, 9_000_000_000_000L);
    private final List<Long> documents = new ArrayList<>();
    private long nextDocument = fixture;
    private NoticeSummaryMaintenance.State savedState;

    record Summary(long count, Instant latestTime, Long latestId, long revision, boolean initialized) { }
    record LockWait(int blockedPid, int blockingPid, String waitEventType, String waitEvent, String observedBlockerChain) { }

    @BeforeEach void saveState() { savedState = maintenance.state(); }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        // Attempt every cleanup step and retain every failure, including state restoration.
        assertAll("notice-summary fixture cleanup",
            this::deleteOwnSummaries, // A failed corruption assertion must not block fixture deletion.
            () -> {
                while (jdbc.update("""
                        DELETE FROM biz_doc_notice WHERE id IN (
                            SELECT id FROM biz_doc_notice WHERE source_type='ORDER_CHANGE' AND source_id=? LIMIT 500)
                        """, fixture) > 0) { }
            },
            this::deleteOwnSummaries,
            () -> {
                if (savedState != null) jdbc.update("""
                        UPDATE biz_doc_notice_pending_state SET backfill_after_type=?,backfill_after_id=?,backfill_complete=?,
                            reconcile_after_type=?,reconcile_after_id=?,reconcile_complete=?,reconcile_mismatches=?,
                            ready=?,use_summary=?,update_time=clock_timestamp() WHERE tenant_key=?
                        """, type(savedState.backfillAfter()), id(savedState.backfillAfter()), savedState.backfillComplete(),
                        type(savedState.reconcileAfter()), id(savedState.reconcileAfter()), savedState.reconcileComplete(),
                        savedState.mismatches(), savedState.ready(), savedState.useSummary(), namespace.tenant());
            });
    }

    @Test void statementChangesAdvanceOnceIncludingNetZeroAndZeroAnchorsRemainReusable() throws Exception {
        long doc = document();
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time)
                SELECT 'PRODUCTION_ORDER',?,'ORDER_CHANGE',?,'P33 bulk',?::timestamptz + n * interval '1 second'
                FROM generate_series(1,3) n RETURNING id
                """, Long.class, doc, fixture, T);
        assertSummary(doc, 3, ids.getLast(), 1);
        // Equal times choose greatest ID, and a genuine sort-tuple update advances once.
        jdbc.update("UPDATE biz_doc_notice SET create_time=? WHERE id=?", T.plusSeconds(2), ids.getLast());
        assertSummary(doc, 3, ids.getLast(), 2);
        jdbc.update("UPDATE biz_doc_notice SET content='P33 changed body',owner_id=? WHERE doc_type=? AND doc_id=?", fixture, TYPE, doc);
        assertSummary(doc, 3, ids.getLast(), 2);
        long history = insert(doc, T.plusSeconds(4), T.plusSeconds(5));
        assertSummary(doc, 3, ids.getLast(), 2);
        jdbc.update("""
                UPDATE biz_doc_notice SET handled_time=CASE WHEN id=? THEN ?::timestamptz ELSE NULL END
                WHERE id IN (?,?)
                """, ids.getLast(), T.plusSeconds(6), ids.getLast(), history);
        assertSummary(doc, 3, history, 3); // Count is unchanged; membership really changed.
        jdbc.update("DELETE FROM biz_doc_notice WHERE id=?", history);
        assertSummary(doc, 2, ids.get(1), 4);
        jdbc.update("DELETE FROM biz_doc_notice WHERE doc_type=? AND doc_id=?", TYPE, doc);
        assertSummary(doc, 0, null, 5);
        long next = insert(doc, T.minusSeconds(1), null);
        assertSummary(doc, 1, next, 6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid
                    AND a.attnum=ANY(c.conkey)
                WHERE c.conrelid='biz_doc_notice_pending'::regclass AND c.contype='f' AND a.attname='latest_notice_id'
                """, Integer.class)).as("derived latest pointer must not lock a source row during FK checks").isZero();
        evidence("statement-revisions.json", Map.of("bulkRows", 3, "final", summary(doc), "latestForeignKeys", 0));
    }

    @Test void readCommittedInsertAndLatestRemovalSerializeInBothOrdersForEarlierLaterAndTiedTimes() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (boolean delete : List.of(false, true)) for (int seconds : List.of(15, 25, 20)) {
            for (boolean insertFirst : List.of(true, false)) {
                long doc = document();
                insert(doc, T.plusSeconds(10), null);
                long latest = insert(doc, T.plusSeconds(20), null);
                long revision = summary(doc).revision();
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try (AutoCloseable cleanup = () -> stop(pool);
                     Connection first = connection(Connection.TRANSACTION_READ_COMMITTED);
                     Connection second = connection(Connection.TRANSACTION_READ_COMMITTED)) {
                    int blocker = backend(first), waiting = backend(second);
                    long added = insertFirst ? insert(first, doc, T.plusSeconds(seconds), null) : 0;
                    if (!insertFirst) remove(first, latest, delete);
                    Future<Long> work = pool.submit(() -> {
                        try {
                            long result = insertFirst ? 0 : insert(second, doc, T.plusSeconds(seconds), null);
                            if (insertFirst) remove(second, latest, delete);
                            second.commit();
                            return result;
                        } catch (Throwable failure) {
                            try { second.rollback(); }
                            catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                            throw failure;
                        }
                    });
                    LockWait wait = awaitBlocked(blocker, waiting, null, work);
                    first.commit();
                    long completed = work.get(10, TimeUnit.SECONDS);
                    long insertedId = insertFirst ? added : completed;
                    assertSummary(doc, 2, insertedId, revision + 2);
                    cases.add(Map.of("deleteLatest", delete, "insertFirst", insertFirst, "insertSecond", seconds,
                            "wait", wait, "final", summary(doc)));
                }
            }
        }
        assertThat(cases).hasSize(12);
        evidence("read-committed-lock-orders.json", cases);
    }

    @Test void bulkNotificationInputOrderDoesNotAcquireHigherSummaryKeyBeforeBlockedLowerKey() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (boolean reversed : List.of(false, true)) {
            long lower = document(), higher = document();
            insert(lower, T, null);
            insert(higher, T, null);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (AutoCloseable cleanup = () -> stop(pool);
                 Connection blocker = connection(Connection.TRANSACTION_READ_COMMITTED);
                 Connection writer = connection(Connection.TRANSACTION_READ_COMMITTED)) {
                insert(blocker, lower, T.plusSeconds(1), null);
                int blockerPid = backend(blocker), writerPid = backend(writer);
                Future<Integer> work = pool.submit(() -> {
                    try {
                        int inserted = update(writer, """
                                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time)
                                SELECT ?::varchar,keys.doc_id,'ORDER_CHANGE',?,'P33 bulk lock order',?::timestamptz
                                FROM (VALUES (?::bigint),(?::bigint)) keys(doc_id)
                                ORDER BY keys.doc_id
                                """ + (reversed ? " DESC" : " ASC"), TYPE, fixture, T.plusSeconds(2), lower, higher);
                        writer.commit();
                        return inserted;
                    } catch (Throwable failure) {
                        try { writer.rollback(); }
                        catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                        throw failure;
                    }
                });
                LockWait wait = awaitBlocked(blockerPid, writerPid, null, work);
                int probePid;
                // A third backend can still lock the higher key while the batch waits on the lower one.
                // This would fail with 55P03 if reverse input caused a higher-first lock sequence.
                try (Connection probe = connection(Connection.TRANSACTION_READ_COMMITTED)) {
                    probePid = backend(probe);
                    assertThat(scalar(probe, """
                            SELECT revision FROM biz_doc_notice_pending
                            WHERE tenant_key=? AND doc_type=? AND doc_id=? FOR UPDATE NOWAIT
                            """, namespace.tenant(), TYPE, higher)).isEqualTo(1);
                    probe.rollback();
                }
                blocker.commit();
                assertThat(work.get(10, TimeUnit.SECONDS)).isEqualTo(2);
                Summary lowerResult = assertMatchesSource(lower), higherResult = assertMatchesSource(higher);
                assertThat(lowerResult.count()).isEqualTo(3);
                assertThat(lowerResult.revision()).isEqualTo(3);
                assertThat(higherResult.count()).isEqualTo(2);
                assertThat(higherResult.revision()).isEqualTo(2);
                cases.add(Map.of("reversedSourceOrder", reversed, "wait", wait, "probePid", probePid,
                        "higherKeyLockAcquiredWhileLowerBlocked", true,
                        "lower", lowerResult, "higher", higherResult));
            }
        }
        evidence("multi-key-lock-order.json", cases);
    }

    @Test void concurrentBusinessHandlersApplyTheCasAndSummaryDeltaOnlyOnce() throws Exception {
        long doc = document(), notice = insert(doc, T, null);
        long revision = summary(doc).revision();
        CountDownLatch handled = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Integer> winnerPid = new CompletableFuture<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<LockWait> waits = new ArrayList<>();
        try (AutoCloseable cleanup = () -> { release.countDown(); stop(pool); }) {
            Future<Long> winner = pool.submit(() -> {
                login(fixture + 100);
                try {
                    transaction().executeWithoutResult(status -> {
                        winnerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        notices.handle(BizType.PRODUCTION_ORDER, doc, notice, "P33 winner");
                        handled.countDown();
                        await(release);
                    });
                    return fixture + 100;
                } finally { SecurityContextHolder.clearContext(); }
            });
            await(handled);
            List<Future<Long>> losers = new ArrayList<>();
            for (int i = 1; i <= 2; i++) {
                long actor = fixture + 100 + i;
                CompletableFuture<Integer> pid = new CompletableFuture<>();
                Future<Long> work = pool.submit(() -> {
                    login(actor);
                    try {
                        transaction().executeWithoutResult(status -> {
                            pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            notices.handle(BizType.PRODUCTION_ORDER, doc, notice, "P33 losing handler");
                        });
                        return actor;
                    } catch (BizException failure) {
                        assertThat(failure.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);
                        return null;
                    } finally { SecurityContextHolder.clearContext(); }
                });
                losers.add(work);
                waits.add(awaitBlocked(winnerPid.get(5, TimeUnit.SECONDS), pid.get(5, TimeUnit.SECONDS), null, work));
            }
            release.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS)).isEqualTo(fixture + 100);
            for (Future<Long> loser : losers) assertThat(loser.get(10, TimeUnit.SECONDS)).isNull();
        }
        assertSummary(doc, 0, null, revision + 1);
        assertThat(jdbc.queryForMap("SELECT handled_by,handle_remark FROM biz_doc_notice WHERE id=?", notice))
                .containsEntry("handled_by", fixture + 100).containsEntry("handle_remark", "P33 winner");
        evidence("business-handle-cas.json", Map.of("handlers", 3, "winners", 1, "waits", waits, "final", summary(doc)));
    }

    @Test void repeatableReadNetZeroMemberChangeRejectsStaleTransactionAndRollsBackUnrelatedWrites() throws Exception {
        long doc = document(), marker = document();
        long earlier = insert(doc, T.plusSeconds(10), null);
        long latest = insert(doc, T.plusSeconds(30), null);
        long history = insert(doc, T.plusSeconds(20), T.plusSeconds(40));
        long revision = summary(doc).revision();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        LockWait wait;
        try (AutoCloseable cleanup = () -> stop(pool);
             Connection old = connection(Connection.TRANSACTION_REPEATABLE_READ);
             Connection changed = connection(Connection.TRANSACTION_READ_COMMITTED)) {
            // Establish RR before the competing net-zero update and write an independent rollback marker.
            scalar(old, "SELECT COUNT(*) FROM biz_doc_notice WHERE doc_type=? AND doc_id=?", TYPE, doc);
            insert(old, marker, T, null);
            update(changed, """
                    UPDATE biz_doc_notice SET handled_time=CASE WHEN id=? THEN ?::timestamptz ELSE NULL END
                    WHERE id IN (?,?)
                    """, earlier, T.plusSeconds(50), earlier, history);
            int blockerPid = backend(changed), oldPid = backend(old);
            Future<SQLException> work = pool.submit(() -> {
                try { remove(old, latest, false); old.commit(); return null; }
                catch (SQLException failure) {
                    try { old.rollback(); }
                    catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); throw failure; }
                    return failure;
                }
            });
            wait = awaitBlocked(blockerPid, oldPid, null, work);
            changed.commit();
            SQLException failure = work.get(10, TimeUnit.SECONDS);
            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("40001");
        }
        assertSummary(doc, 2, latest, revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE doc_type=? AND doc_id=?", Integer.class, TYPE, marker)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=?", Integer.class, namespace.tenant(), TYPE, marker)).isZero();
        try (Connection fresh = connection(Connection.TRANSACTION_REPEATABLE_READ)) {
            insert(fresh, marker, T, null);
            remove(fresh, latest, false);
            fresh.commit();
        }
        assertSummary(doc, 1, history, revision + 2);
        assertThat(summary(marker).count()).isEqualTo(1);
        evidence("repeatable-read-restart.json", Map.of("wait", wait, "sqlState", "40001",
                "independentWriteRolledBack", true, "freshTransactionSucceeded", true, "final", summary(doc)));
    }

    @Test void concurrentFirstAnchorPreservesRcRowsAndRequiresFreshRrTransaction() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int isolation : List.of(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ)) {
            long doc = document();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (AutoCloseable cleanup = () -> stop(pool);
                 Connection second = connection(isolation); Connection first = connection(Connection.TRANSACTION_READ_COMMITTED)) {
                scalar(second, "SELECT COUNT(*) FROM biz_doc_notice WHERE doc_type=? AND doc_id=?", TYPE, doc);
                long firstId = insert(first, doc, T, null);
                int blocker = backend(first), blocked = backend(second);
                Future<String> work = pool.submit(() -> {
                    try { insert(second, doc, T.plusSeconds(1), null); second.commit(); return "committed"; }
                    catch (SQLException failure) {
                        try { second.rollback(); }
                        catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); throw failure; }
                        return failure.getSQLState();
                    }
                });
                LockWait wait = awaitBlocked(blocker, blocked, null, work);
                first.commit();
                String outcome = work.get(10, TimeUnit.SECONDS);
                if (isolation == Connection.TRANSACTION_READ_COMMITTED) {
                    assertThat(outcome).isEqualTo("committed");
                    assertThat(assertMatchesSource(doc).count()).isEqualTo(2);
                    assertThat(summary(doc).revision()).isEqualTo(2);
                } else {
                    assertThat(outcome).isEqualTo("40001");
                    assertSummary(doc, 1, firstId, 1);
                    try (Connection retry = connection(Connection.TRANSACTION_REPEATABLE_READ)) {
                        insert(retry, doc, T.plusSeconds(1), null); retry.commit();
                    }
                    assertThat(assertMatchesSource(doc).count()).isEqualTo(2);
                }
                results.add(Map.of("isolation", isolation, "wait", wait, "initialOutcome", outcome, "final", summary(doc)));
            }
        }
        evidence("first-anchor-races.json", results);
    }

    @Test void boundedBackfillSeesCommittedChangesAfterLockWaitAndResumesWithoutSkippingConcurrentKeys() throws Exception {
        List<Long> docs = new ArrayList<>();
        for (int i = 0; i < 503; i++) docs.add(document());
        assertThat(jdbc.update("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time)
                SELECT 'PRODUCTION_ORDER',?+n,'ORDER_CHANGE',?,'P33 legacy',?::timestamptz
                FROM generate_series(0,502) n
                """, docs.getFirst(), fixture, T)).isEqualTo(503);
        deleteOwnSummaries(); // Simulate rows committed before V61, with all source triggers now active.
        maintenance.restartBackfill();
        advanceBefore(docs.getFirst());
        long behindCursor = docs.getFirst() - 1;
        documents.add(behindCursor);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        LockWait wait;
        try (AutoCloseable cleanup = () -> stop(pool);
             Connection writer = connection(Connection.TRANSACTION_READ_COMMITTED)) {
            insert(writer, docs.getFirst(), T.plusSeconds(1), null);
            Future<NoticeSummaryMaintenance.BatchProgress> batch = pool.submit(() -> maintenance.backfillBatch(500));
            wait = awaitBlocked(backend(writer), null, "scm_notice_pending_backfill", batch);
            update(writer, "DELETE FROM biz_doc_notice WHERE doc_type=? AND doc_id=?", TYPE, docs.get(1));
            insert(writer, behindCursor, T, null); // Not in the already-open key-page snapshot.
            writer.commit();
            var first = batch.get(10, TimeUnit.SECONDS);
            assertThat(first.processed()).isEqualTo(500);
            assertThat(first.complete()).isFalse();
        }
        var paused = maintenance.state();
        assertThat(paused.backfillAfter()).isEqualTo(new NoticeSummaryMaintenance.Position(TYPE, docs.get(499)));
        assertThat(paused.backfillComplete()).isFalse();
        assertThat(maintenance.state()).isEqualTo(paused); // Progress is durable between independent calls.
        assertThat(assertMatchesSource(docs.getFirst()).count()).isEqualTo(2);
        assertThat(assertMatchesSource(docs.get(1)).count()).isZero();
        assertThat(assertMatchesSource(behindCursor).count()).isEqualTo(1);
        finishBackfill();
        for (long doc : docs) assertMatchesSource(doc);
        var completed = maintenance.state();
        Summary beforeRepeat = summary(docs.getFirst());
        assertThat(maintenance.backfillBatch(500)).isEqualTo(new NoticeSummaryMaintenance.BatchProgress(0, true, 0));
        assertThat(maintenance.state()).isEqualTo(completed);
        assertThat(summary(docs.getFirst())).isEqualTo(beforeRepeat);
        maintenance.startReconciliation();
        finishReconciliation();
        assertThat(maintenance.state().mismatches()).isZero();
        assertThat(maintenance.state().ready()).isTrue();
        evidence("backfill-concurrent-resume.json", Map.of("legacyKeys", 503, "firstBatch", 500,
                "wait", wait, "paused", paused, "completed", maintenance.state(), "behindCursorMaintained", true));
    }

    @Test void maintenanceLockTimeoutRollsBackWholeBatchAndDoesNotAdvanceCursor() throws Exception {
        long firstDoc = document(), secondDoc = document();
        insert(firstDoc, T, null); insert(secondDoc, T, null);
        maintenance.restartBackfill();
        advanceBefore(firstDoc);
        var before = maintenance.state();
        Summary first = summary(firstDoc), second = summary(secondDoc);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        LockWait wait;
        try (AutoCloseable cleanup = () -> stop(pool);
             Connection blocker = connection(Connection.TRANSACTION_READ_COMMITTED)) {
            scalar(blocker, "SELECT revision FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=? FOR UPDATE",
                    namespace.tenant(), TYPE, secondDoc);
            Future<Throwable> batch = pool.submit(() -> catchThrowable(() -> maintenance.backfillBatch(2)));
            wait = awaitBlocked(backend(blocker), null, "scm_notice_pending_backfill", batch);
            Throwable failure = batch.get(8, TimeUnit.SECONDS); // The repository's original 2s lock budget must fire.
            assertThat(failure).isInstanceOf(DataAccessException.class);
            assertThat(sqlState(failure)).isEqualTo("55P03");
            assertThat(maintenance.state()).isEqualTo(before);
            assertThat(summary(firstDoc)).isEqualTo(first); // Earlier work in this failed batch also rolled back.
            assertThat(summary(secondDoc)).isEqualTo(second);
            blocker.rollback();
        }
        assertThat(maintenance.backfillBatch(2).processed()).isEqualTo(2);
        assertThat(summary(firstDoc).revision()).isEqualTo(first.revision() + 1);
        assertThat(summary(secondDoc).revision()).isEqualTo(second.revision() + 1);
        evidence("maintenance-failed-batch.json", Map.of("wait", wait, "sqlState", "55P03", "cursorRolledBack", true,
                "previousKeyRolledBack", true, "resumed", maintenance.state()));
    }

    @Test void reconciliationRejectsMismatchAndEarlySwitchAndFallbackContinuesMaintaining() throws Exception {
        long doc = document();
        insert(doc, T, null);
        maintenance.restartBackfill();
        assertThatThrownBy(() -> maintenance.backfillBatch(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> maintenance.backfillBatch(501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> maintenance.reconcileBatch(501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(maintenance::startReconciliation).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> maintenance.setUseSummary(true)).isInstanceOf(DataAccessException.class);
        assertThat(maintenance.state().backfillAfter()).isNull();
        finishBackfill();
        assertThat(maintenance.state().ready()).isFalse();
        jdbc.update("UPDATE biz_doc_notice_pending SET pending_count=pending_count+1 WHERE tenant_key=? AND doc_type=? AND doc_id=?", namespace.tenant(), TYPE, doc);
        maintenance.startReconciliation();
        finishReconciliation();
        var mismatch = maintenance.state();
        assertThat(mismatch.mismatches()).isEqualTo(1);
        assertThat(mismatch.ready()).isFalse();
        assertThat(summary(doc).count()).isEqualTo(2); // Reconciliation reports, it does not silently repair.
        assertThatThrownBy(() -> maintenance.setUseSummary(true)).isInstanceOf(DataAccessException.class);
        maintenance.restartBackfill();
        finishBackfill();
        maintenance.startReconciliation();
        var firstPage = maintenance.reconcileBatch(1);
        assertThat(firstPage.processed()).isEqualTo(1);
        assertThat(firstPage.complete()).isFalse();
        var paused = maintenance.state();
        assertThat(maintenance.state()).isEqualTo(paused);
        finishReconciliation();
        assertThat(maintenance.state().ready()).isTrue();
        assertThat(maintenance.state().mismatches()).isZero();
        assertThat(maintenance.reconcileBatch(500)).isEqualTo(new NoticeSummaryMaintenance.BatchProgress(0, true, 0));
        maintenance.setUseSummary(true);
        assertThat(maintenance.readNamespace()).contains(namespace.tenant());
        maintenance.setUseSummary(false);
        assertThat(maintenance.readNamespace()).isEmpty();
        long revision = summary(doc).revision();
        long added = insert(doc, T.plusSeconds(1), null);
        assertSummary(doc, 2, added, revision + 1);
        jdbc.update("DELETE FROM biz_doc_notice WHERE id=?", added);
        assertThat(assertMatchesSource(doc).count()).isEqualTo(1);
        assertThat(summary(doc).revision()).isEqualTo(revision + 2);
        maintenance.setUseSummary(true);
        assertThat(maintenance.state().useSummary()).isTrue();
        evidence("reconcile-gates-and-fallback.json", Map.of("mismatch", mismatch, "paused", paused,
                "repaired", maintenance.state(), "fallbackWritesMaintained", true));
    }

    private long document() { long id = ++nextDocument; documents.add(id); return id; }

    private long insert(long doc, OffsetDateTime at, OffsetDateTime handled) {
        return jdbc.queryForObject("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time,handled_time)
                VALUES(? ,?,'ORDER_CHANGE',?,'P33 fixture',?,?) RETURNING id
                """, Long.class, TYPE, doc, fixture, at, handled);
    }

    private long insert(Connection connection, long doc, OffsetDateTime at, OffsetDateTime handled) throws SQLException {
        return scalar(connection, """
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,create_time,handled_time)
                VALUES(? ,?,'ORDER_CHANGE',?,'P33 concurrent fixture',?,?) RETURNING id
                """, TYPE, doc, fixture, at, handled);
    }

    private static void remove(Connection connection, long notice, boolean delete) throws SQLException {
        assertThat(update(connection, delete ? "DELETE FROM biz_doc_notice WHERE id=?"
                : "UPDATE biz_doc_notice SET handled_time=clock_timestamp() WHERE id=? AND handled_time IS NULL", notice)).isEqualTo(1);
    }

    private Connection connection(int isolation) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='8s'");
                statement.execute("SET LOCAL statement_timeout='10s'");
            }
            return connection;
        } catch (Throwable failure) {
            try { connection.close(); }
            catch (SQLException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate result = new TransactionTemplate(transactions);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        result.setTimeout(15);
        return result;
    }

    private static int backend(Connection connection) throws SQLException {
        return Math.toIntExact(scalar(connection, "SELECT pg_backend_pid()"));
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }

    private static long scalar(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = prepare(connection, sql, args); var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = prepare(connection, sql, args)) { return statement.executeUpdate(); }
    }

    private LockWait awaitBlocked(int blocker, Integer waiting, String query, Future<?> work) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<LockWait> found = jdbc.query("""
                    WITH RECURSIVE waiting AS (
                        SELECT pid,wait_event_type,wait_event FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                            AND (?::integer IS NULL OR pid=?) AND (?::text IS NULL OR query LIKE '%' || ? || '%')
                    ), blockers AS (
                        SELECT w.pid AS waiting_pid,unnest(pg_blocking_pids(w.pid)) AS blocker_pid FROM waiting w
                        UNION SELECT b.waiting_pid,unnest(pg_blocking_pids(b.blocker_pid)) FROM blockers b
                    )
                    SELECT w.*,(SELECT string_agg(b.blocker_pid::text,',' ORDER BY b.blocker_pid)
                        FROM blockers b WHERE b.waiting_pid=w.pid) AS blocker_chain
                    FROM waiting w WHERE EXISTS (
                        SELECT 1 FROM blockers b WHERE b.waiting_pid=w.pid AND b.blocker_pid=?)
                    """, (row, index) -> new LockWait(row.getInt("pid"), blocker, row.getString("wait_event_type"),
                            row.getString("wait_event"), row.getString("blocker_chain")), waiting, waiting, query, query, blocker);
            if (!found.isEmpty()) { assertThat(found).hasSize(1); return found.getFirst(); }
            if (work.isDone()) { work.get(1, TimeUnit.SECONDS); throw new AssertionError("Writer finished without the required database lock wait"); }
            Thread.yield(); // Poll the actual server state; elapsed time alone never satisfies the barrier.
        }
        throw new AssertionError("No pg_stat_activity / pg_blocking_pids proof of the intended lock wait");
    }

    private Summary summary(long doc) {
        return jdbc.queryForObject("""
                SELECT pending_count,latest_time,latest_notice_id,revision,initialized
                FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=?
                """, (row, index) -> {
            OffsetDateTime time = row.getObject("latest_time", OffsetDateTime.class);
            return new Summary(row.getLong("pending_count"), time == null ? null : time.toInstant(),
                    row.getObject("latest_notice_id", Long.class), row.getLong("revision"), row.getBoolean("initialized"));
        }, namespace.tenant(), TYPE, doc);
    }

    private Summary assertMatchesSource(long doc) {
        Summary actual = summary(doc);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM biz_doc_notice WHERE doc_type=? AND doc_id=? AND handled_time IS NULL", Long.class, TYPE, doc);
        List<Map<String, Object>> latest = jdbc.query("""
                SELECT id,create_time FROM biz_doc_notice WHERE doc_type=? AND doc_id=? AND handled_time IS NULL
                ORDER BY create_time DESC,id DESC LIMIT 1
                """, (row, index) -> Map.<String, Object>of("id", row.getLong("id"), "time", row.getObject("create_time", OffsetDateTime.class).toInstant()), TYPE, doc);
        assertThat(actual.initialized()).isTrue();
        assertThat(actual.count()).isEqualTo(count);
        assertThat(actual.latestId()).isEqualTo(latest.isEmpty() ? null : latest.getFirst().get("id"));
        assertThat(actual.latestTime()).isEqualTo(latest.isEmpty() ? null : latest.getFirst().get("time"));
        return actual;
    }

    private void assertSummary(long doc, long count, Long latest, long revision) {
        Summary actual = assertMatchesSource(doc);
        assertThat(actual.count()).isEqualTo(count);
        assertThat(actual.latestId()).isEqualTo(latest);
        assertThat(actual.revision()).isEqualTo(revision);
    }

    private void advanceBefore(long doc) {
        long remaining = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT doc_type,doc_id FROM biz_doc_notice WHERE handled_time IS NULL
                    UNION SELECT doc_type,doc_id FROM biz_doc_notice_pending WHERE tenant_key=?
                ) keys WHERE (doc_type,doc_id)<(?,?)
                """, Long.class, namespace.tenant(), TYPE, doc);
        while (remaining > 0) {
            int limit = (int) Math.min(500, remaining);
            var progress = maintenance.backfillBatch(limit);
            assertThat(progress.processed()).isEqualTo(limit);
            assertThat(progress.complete()).isFalse();
            remaining -= limit;
        }
    }

    private int batchBudget() {
        long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT doc_type,doc_id FROM biz_doc_notice WHERE handled_time IS NULL
                    UNION SELECT doc_type,doc_id FROM biz_doc_notice_pending WHERE tenant_key=?) keys
                """, Long.class, namespace.tenant());
        return Math.toIntExact(count / 500 + 2);
    }

    private void finishBackfill() {
        for (int left = batchBudget(); left > 0; left--) {
            var progress = maintenance.backfillBatch(500);
            assertThat(progress.processed()).isBetween(0, 500);
            if (progress.complete()) return;
        }
        throw new AssertionError("Backfill did not terminate within the actual key-count batch bound");
    }

    private void finishReconciliation() {
        for (int left = batchBudget(); left > 0; left--) {
            var progress = maintenance.reconcileBatch(500);
            assertThat(progress.processed()).isBetween(0, 500);
            if (progress.complete()) return;
        }
        throw new AssertionError("Reconciliation did not terminate within the actual key-count batch bound");
    }

    private void deleteOwnSummaries() {
        if (!documents.isEmpty()) jdbc.batchUpdate("DELETE FROM biz_doc_notice_pending WHERE tenant_key=? AND doc_type=? AND doc_id=?",
                documents.stream().map(doc -> new Object[]{namespace.tenant(), TYPE, doc}).toList());
    }

    private static String type(NoticeSummaryMaintenance.Position position) { return position == null ? null : position.docType(); }
    private static Long id(NoticeSummaryMaintenance.Position position) { return position == null ? null : position.docId(); }
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }
    private static void login(long actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(actor, "p33-it"), null, List.of()));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertThat(pool.awaitTermination(12, TimeUnit.SECONDS)).isTrue();
    }
    private void evidence(String name, Object value) throws Exception {
        Path folder = Path.of("test-artifacts/full-audit/s1/notice-summary");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
