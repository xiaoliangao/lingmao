package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.dev.core.SampleStates;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.security.DataScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多条已提交 PG 连接验证真实领域锁和持久化；HTTP 幂等/权限/DTO 另由 s2 实测。 */
@IntegrationTest
class S1SampleRepositoryIT extends SampleTestSupport {
    @Test
    void twentyConcurrentDraftsUseRealAtomicBusinessNumbersAndCommitCompleteSelections() throws Exception {
        Fixture f = fixture(); AtomicInteger active = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
        record Outcome(Created created, int backend) { }
        List<Outcome> result = parallel(20, index -> {
            login(f.creator(), true);
            return transactions.execute(status -> {
                int running = active.incrementAndGet(); peak.accumulateAndGet(running, Math::max);
                try { int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    return new Outcome(createDraftInTransaction(f), pid);
                } finally { active.decrementAndGet(); }
            });
        });
        assertThat(result.stream().map(x -> x.created().sampleCode()).distinct()).hasSize(20);
        assertThat(result.stream().map(x -> x.created().orderNo()).distinct()).hasSize(20);
        assertThat(result.stream().map(Outcome::backend).distinct().count()).isGreaterThanOrEqualTo(2);
        assertThat(peak.get()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample WHERE company_id=? AND deleted_token=0", Long.class, f.company())).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order_color WHERE company_id=?", Long.class, f.company())).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order_size WHERE company_id=?", Long.class, f.company())).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT sum(qty) FROM dev_sample_order WHERE company_id=?", Long.class, f.company())).isEqualTo(20);
        evidence("twenty-real-draft-transactions.json", Map.of("results", result, "peakTransactions", peak.get()));
    }

    @Test
    void twoStaleEditorsOfOneSampleHaveOneSuccessAndOneCasConflict() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        List<Integer> codes = parallel(2, index -> {
            login(f.creator(), true);
            try { transactions.executeWithoutResult(status -> {
                var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
                lock.sample().setName("并发编辑" + index); samples.updateSample(lock, lock.sample());
            }); return 0; } catch (BizException ex) { return ex.getCode(); }
        });
        assertThat(codes).containsExactlyInAnyOrder(0, 1002);
        var stored = snapshot(f, made.sampleId());
        assertThat(stored.sample().getVersion()).isEqualTo(1); assertThat(stored.order().getVersion()).isZero();
        evidence("sample-cas-conflict.json", Map.of("codes", codes, "sample", stored.sample()));
    }

    @Test
    void pendingMasterColorChangeMustKeepActualOrderSelectionValidInTheSameTransaction() {
        Fixture f = fixture(); Created made = draft(f);
        List<Long> remaining = List.of(f.colors().get(1).id(), f.colors().get(2).id());
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, 0);
            samples.replaceColors(lock.sample(), remaining);
            samples.updateSample(lock, lock.sample()); // 故意未同步待派单所选首色，提交不能留下失效关联。
        }));
        assertThat(samples.colors(f.company(), made.sampleId(), DataScope.current(false))).hasSize(3);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, 0);
            samples.replaceColors(lock.sample(), remaining);
            samples.replaceSelection(lock.order(), List.of(f.colors().get(1)), List.of("M"));
            samples.updateOrder(lock, lock.order()); samples.updateSample(lock, lock.sample());
        });
        var current = snapshot(f, made.sampleId());
        assertThat(current.sample().getVersion()).isEqualTo(1); assertThat(current.order().getVersion()).isEqualTo(1);
        assertThat(samples.colors(f.company(), made.sampleId(), DataScope.current(false))).extracting(c -> c.colorId()).containsExactlyElementsOf(remaining);
        assertThat(samples.bundle(f.company(), DataScope.current(false), List.of(made.orderId())).colors())
                .extracting(c -> c.colorId()).containsExactly(f.colors().get(1).id());
    }

    @Test
    void internalAndExternalDispatchKeepStableNodeSnapshotsAndOnlyTotalQuantity() throws Exception {
        Fixture f = fixture(); Created internal = draft(f); Created external = draft(f);
        dispatch(f, internal.sampleId(), MakeWay.INTERNAL); dispatch(f, external.sampleId(), MakeWay.EXTERNAL);
        var result = samples.bundle(f.company(), DataScope.current(false), List.of(internal.orderId(), external.orderId()));
        assertThat(result.nodes()).hasSize(10);
        assertThat(result.nodes().stream().filter(n -> n.getSampleOrderId() == internal.orderId())).allSatisfy(n -> assertThat(n.getOwnerId()).isEqualTo(f.maker()));
        assertThat(result.nodes().stream().filter(n -> n.getSampleOrderId() == external.orderId())).allSatisfy(n -> assertThat(n.getOwnerId()).isEqualTo(f.designer()));
        assertThat(result.orders()).allSatisfy(o -> {
            assertThat(o.getQty()).isEqualTo(1); assertThat(o.getVersion()).isEqualTo(1); assertThat(o.getDispatchedTime()).isNotNull();
        });
        assertThat(result.nodes().stream().filter(n -> n.getNodeCode().equals("SEND")))
                .allSatisfy(n -> assertThat(n.getPlanDate()).isEqualTo(LocalDate.now(clock).plusDays(10)));
        evidence("dispatch-node-snapshots.json", result);
    }

    @Test
    void sendCanSkipEarlierNodesAndUndoMustAdvanceBothVersions() {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        var before = snapshot(f, made.sampleId());
        assertThat(before.order().getStatus()).isEqualTo(SampleOrderStatus.WAIT_REVIEW);
        var rows = samples.bundle(f.company(), DataScope.current(false), List.of(made.orderId())).nodes();
        assertThat(rows.stream().filter(n -> n.getDoneDate() == null).count()).isEqualTo(4);
        assertThat(before.sample().getVersion()).isEqualTo(2); assertThat(before.order().getVersion()).isEqualTo(2);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> samples.lockByOrder(f.company(), made.orderId(),
                DataScope.current(false), 1, 1))).isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(1002));
        transactions.executeWithoutResult(status -> {
            var lock = samples.lockByOrder(f.company(), made.orderId(), DataScope.current(false), 2, 2);
            var node = samples.lockNodes(lock).stream().filter(n -> n.getNodeCode().equals("SEND")).findFirst().orElseThrow();
            samples.setDone(lock, node.getId(), null); lock.order().setStatus(SampleOrderStatus.MAKING);
            samples.updateOrder(lock, lock.order()); samples.touchSample(lock);
        });
        var after = snapshot(f, made.sampleId());
        assertThat(after.order().getStatus()).isEqualTo(SampleOrderStatus.MAKING);
        assertThat(after.sample().getVersion()).isEqualTo(3); assertThat(after.order().getVersion()).isEqualTo(3);
    }

    @Test
    void twentyConcurrentRevisionAttemptsCreateOneReviewAndOneNextVersion() throws Exception {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.EXTERNAL); send(f, made.sampleId());
        AtomicInteger active = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
        record Attempt(int code, int backend) { }
        List<Attempt> attempts = parallel(20, index -> {
            login(f.creator(), true); AtomicInteger pid = new AtomicInteger();
            try {
                transactions.executeWithoutResult(status -> {
                    int value = active.incrementAndGet(); peak.accumulateAndGet(value, Math::max);
                    try { pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        var lock = samples.lockByOrder(f.company(), made.orderId(), DataScope.current(false), 2, 2);
                        reviewLocked(f, lock, SampleReviewResult.REVISE);
                    } finally { active.decrementAndGet(); }
                }); return new Attempt(0, pid.get());
            } catch (BizException ex) { return new Attempt(ex.getCode(), pid.get()); }
        });
        assertThat(attempts.stream().filter(a -> a.code() == 0).count()).isEqualTo(1);
        assertThat(attempts.stream().filter(a -> a.code() == 1002).count()).isEqualTo(19);
        assertThat(attempts.stream().map(Attempt::backend).distinct().count()).isGreaterThanOrEqualTo(2);
        assertThat(peak.get()).isGreaterThanOrEqualTo(2);
        var stored = snapshot(f, made.sampleId());
        assertThat(stored.order().getSampleVersion()).isEqualTo(2); assertThat(stored.order().getStatus()).isEqualTo(SampleOrderStatus.WAIT_ASSIGN);
        assertThat(stored.order().getFactoryId()).isEqualTo(f.factory()); assertThat(stored.order().getReason()).isEqualTo("S1 评审意见");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?", Long.class, made.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?", Long.class, made.sampleId())).isEqualTo(2);
        evidence("twenty-revision-cas.json", Map.of("attempts", attempts, "peakTransactions", peak.get(), "stored", stored));
    }

    @Test
    void reviewAndStopReallyOverlapOnSeparateConnectionsAndOnlyOneCommits() throws Exception {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        CyclicBarrier inTransactions = new CyclicBarrier(2);
        record Attempt(int code, int backend) { }
        List<Attempt> attempts = parallel(2, index -> {
            login(f.creator(), true); AtomicInteger pid = new AtomicInteger();
            try { transactions.executeWithoutResult(status -> {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try { inTransactions.await(5, TimeUnit.SECONDS); } catch (Exception ex) { throw new AssertionError(ex); }
                var lock = samples.lockByOrder(f.company(), made.orderId(), DataScope.current(false), 2, 2);
                if (index == 0) reviewLocked(f, lock, SampleReviewResult.PASS);
                else { lock.order().setStatus(SampleOrderStatus.CANCELLED); samples.updateOrder(lock, lock.order());
                    lock.sample().setStatus(SampleStatus.ELIMINATED); samples.updateSample(lock, lock.sample()); }
            }); return new Attempt(0, pid.get()); } catch (BizException ex) { return new Attempt(ex.getCode(), pid.get()); }
        });
        assertThat(attempts).extracting(Attempt::code).containsExactlyInAnyOrder(0, 1002);
        assertThat(attempts.stream().map(Attempt::backend).distinct()).hasSize(2);
        var stored = snapshot(f, made.sampleId()); SampleStates.requirePair(stored.sample().getStatus(), stored.order().getStatus());
        long expected = stored.order().getStatus() == SampleOrderStatus.PASSED ? 1 : 0;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?", Long.class, made.orderId())).isEqualTo(expected);
        evidence("review-stop-race.json", Map.of("attempts", attempts, "stored", stored));
    }

    @Test
    void revisionFailureRollsBackReviewSelectionsStateAndBusinessCounter() {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        String dayKey = "DY" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        int next = serials.peek(dayKey);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lockByOrder(f.company(), made.orderId(), DataScope.current(false), 2, 2);
            reviewLocked(f, lock, SampleReviewResult.REVISE); status.setRollbackOnly();
        });
        assertThat(serials.peek(dayKey)).isEqualTo(next);
        var stored = snapshot(f, made.sampleId());
        assertThat(stored.sample().getVersion()).isEqualTo(2); assertThat(stored.order().getVersion()).isEqualTo(2);
        assertThat(stored.order().getStatus()).isEqualTo(SampleOrderStatus.WAIT_REVIEW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?", Long.class, made.sampleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?", Long.class, made.orderId())).isZero();
    }

    @Test
    void completedReviewsAndReviewerMembershipRejectUpdateDeleteTruncateAndLateInsert() throws Exception {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        review(f, made.sampleId(), SampleReviewResult.PASS);
        long review = jdbc.queryForObject("SELECT id FROM dev_sample_review WHERE sample_order_id=?", Long.class, made.orderId());
        rejected("23514", () -> jdbc.update("UPDATE dev_sample_review SET opinion='改写历史' WHERE id=?", review));
        rejected("23514", () -> jdbc.update("DELETE FROM dev_sample_review WHERE id=?", review));
        rejected("23514", () -> jdbc.execute("TRUNCATE dev_sample_review,dev_sample_reviewer"));
        rejected("23514", () -> jdbc.update("UPDATE dev_sample_reviewer SET seq=99 WHERE review_id=?", review));
        rejected("23514", () -> jdbc.update("DELETE FROM dev_sample_reviewer WHERE review_id=?", review));
        rejected("23514", () -> jdbc.update("INSERT INTO dev_sample_reviewer(tenant_key,company_id,review_id,user_id,seq) VALUES(?,?,?,?,3)",
                namespace.tenant(), f.company(), review, f.otherUser()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_reviewer WHERE review_id=?", Long.class, review)).isEqualTo(2);
        evidence("immutable-review.json", Map.of("reviewId", review, "reviewers", jdbc.queryForList("SELECT user_id,seq FROM dev_sample_reviewer WHERE review_id=? ORDER BY seq", review)));
    }

    @Test
    void dispatchedSelectionsStayReadableAfterMasterColorRenameAndRemoval() {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL);
        var original = f.colors().getFirst();
        jdbc.update("UPDATE bas_color SET name=? WHERE id=?", unique("新色名"), original.id());
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 1, null);
            samples.replaceColors(lock.sample(), f.colors().subList(1, 3).stream().map(SampleRepository.ColorChoice::id).toList());
            samples.updateSample(lock, lock.sample());
        });
        var colors = samples.bundle(f.company(), DataScope.current(false), List.of(made.orderId())).colors();
        assertThat(colors).singleElement().satisfies(color -> {
            assertThat(color.colorId()).isEqualTo(original.id()); assertThat(color.colorName()).isEqualTo(original.name());
            assertThat(color.colorCode()).isEqualTo(original.code());
        });
        rejected("23514", () -> jdbc.update("UPDATE dev_sample_order_color SET color_name='伪快照' WHERE sample_order_id=?", made.orderId()));
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 2, null);
            lock.sample().setCompanyId(f.otherCompany()); samples.updateSample(lock, lock.sample());
        }));
    }

    @Test
    void selfScopeUsesCreatorDesignerOrCurrentMakerAndBatchRejectsMixedCompany() {
        Fixture f = fixture(); Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL);
        for (long user : List.of(f.creator(), f.designer(), f.maker())) {
            login(user, false); assertThat(samples.read(f.company(), made.sampleId(), DataScope.current(false)).sample().getId()).isEqualTo(made.sampleId());
        }
        login(f.otherUser(), false);
        assertThatThrownBy(() -> samples.read(f.company(), made.sampleId(), DataScope.current(false)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        Fixture other = fixture(); Created foreign = draft(other); login(f.creator(), true);
        assertThatThrownBy(() -> samples.bundle(f.company(), DataScope.current(false), List.of(made.orderId(), foreign.orderId())))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
    }

    @Test
    void unassignedCompanyMoveUpdatesEverySelectionAndThenDraftDeletionPreservesIdentity() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
            lock.sample().setCompanyId(f.otherCompany()); samples.updateSample(lock, lock.sample());
        });
        var moved = samples.read(f.otherCompany(), made.sampleId(), DataScope.current(false));
        assertThat(moved.order().getCompanyId()).isEqualTo(f.otherCompany());
        assertThat(jdbc.queryForList("SELECT DISTINCT company_id FROM dev_sample_order_color WHERE sample_order_id=?", Long.class, made.orderId())).containsExactly(f.otherCompany());
        assertThat(jdbc.queryForList("SELECT DISTINCT company_id FROM dev_sample_order_size WHERE sample_order_id=?", Long.class, made.orderId())).containsExactly(f.otherCompany());
        transactions.executeWithoutResult(status -> samples.deleteDraft(samples.lock(f.otherCompany(), made.sampleId(), DataScope.current(false), 1, null)));
        assertThat(samples.replayIdentity(f.otherCompany(), made.sampleId(), DataScope.current(false)).getDeletedToken()).isEqualTo(made.sampleId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?", Long.class, made.sampleId())).isEqualTo(1);
        assertThatThrownBy(() -> samples.read(f.otherCompany(), made.sampleId(), DataScope.current(false)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        rejected("23514", () -> jdbc.update("DELETE FROM dev_sample WHERE id=?", made.sampleId()));
        evidence("company-move-tombstone.json", Map.of("sampleId", made.sampleId(), "oldCompany", f.company(), "newCompany", f.otherCompany()));
    }

    @Test
    void draftSoftDeleteAllowsSameCodeRecreationAndRollbackDoesNotConsumeSerial() {
        Fixture f = fixture(); Created made = draft(f);
        transactions.executeWithoutResult(status -> samples.deleteDraft(samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null)));
        transactions.executeWithoutResult(status -> {
            var replacement = sample(f); replacement.setCode(made.sampleCode()); samples.insertSample(replacement);
            samples.replaceColors(replacement, List.of(f.colors().getFirst().id()));
            var order = order(f, replacement.getId(), 1); samples.insertOrder(order);
            samples.replaceSelection(order, List.of(f.colors().getFirst()), List.of("M"));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample WHERE tenant_key=? AND code=?", Long.class, namespace.tenant(), made.sampleCode())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample WHERE tenant_key=? AND code=? AND deleted_token=0", Long.class, namespace.tenant(), made.sampleCode())).isEqualTo(1);
    }

    @Test
    void commitRejectsMissingFirstOrderHalfTransitionsAndNegativeQuantity() {
        Fixture f = fixture(); login(f.creator(), true);
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var sample = sample(f); sample.setCode(unique("YY")); samples.insertSample(sample);
            samples.replaceColors(sample, List.of(f.colors().getFirst().id()));
        }));
        Created made = draft(f);
        rejected("23514", () -> jdbc.update("UPDATE dev_sample_order SET qty=0,version=version+1 WHERE id=?", made.orderId()));
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
            lock.sample().setStatus(SampleStatus.PASSED); samples.updateSample(lock, lock.sample());
        }));
        assertThat(snapshot(f, made.sampleId()).sample().getVersion()).isZero();
    }

    @Test
    void historySeekReturnsOneHundredThenRemainderWithoutReusingOldVersionForWrites() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        for (int i = 0; i < 101; i++) stopAndReopen(f, made.sampleId());
        var first = samples.versions(f.company(), made.sampleId(), DataScope.current(false), null, 100);
        assertThat(first.records()).hasSize(100); assertThat(first.hasMore()).isTrue(); assertThat(first.nextBeforeVersion()).isEqualTo(3);
        var second = samples.versions(f.company(), made.sampleId(), DataScope.current(false), first.nextBeforeVersion(), 100);
        assertThat(second.records()).extracting(o -> o.getSampleVersion()).containsExactly(2, 1); assertThat(second.hasMore()).isFalse();
        assertThat(first.records().stream().map(o -> o.getId()).filter(second.records().stream().map(o -> o.getId()).collect(java.util.stream.Collectors.toSet())::contains)).isEmpty();
        var current = snapshot(f, made.sampleId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> samples.lockByOrder(f.company(), made.orderId(), DataScope.current(false),
                current.sample().getVersion(), 1))).isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(1002));
        assertThatThrownBy(() -> samples.versions(f.company(), made.sampleId(), DataScope.current(false), null, 101))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        evidence("sample-version-seek.json", Map.of("firstIds", first.records().stream().map(o -> o.getId()).toList(),
                "cursor", first.nextBeforeVersion(), "next", second));
    }
}
