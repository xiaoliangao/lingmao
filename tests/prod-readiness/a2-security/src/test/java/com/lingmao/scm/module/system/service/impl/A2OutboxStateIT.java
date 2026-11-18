package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class A2OutboxStateIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private DeploymentNamespace namespace() { return new DeploymentNamespace("o2_" + UUID.randomUUID().toString().replace("-", "")); }
    private OutboxStore store(DeploymentNamespace namespace) { return new OutboxStore(jdbc, namespace, transactions); }
    private long enqueue(OutboxStore store) { return store.enqueue("CACHE_INVALIDATE", UUID.randomUUID().toString(), "\"key\"", null, "a2-outbox"); }

    @Test void messageAndBusinessMutationCommitOrRollbackTogether() {
        var store = store(namespace()); var tx = new TransactionTemplate(transactions);
        String type = "o2_" + UUID.randomUUID().toString().replace("-", "");
        long[] rolled = new long[1];
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sys_dict_type(code,name) VALUES(?,?)", type, "rollback");
            rolled[0] = enqueue(store); status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_dict_type WHERE code=?", Long.class, type)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox WHERE id=?", Long.class, rolled[0])).isZero();
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sys_dict_type(code,name) VALUES(?,?)", type, "committed");
            rolled[0] = enqueue(store);
        });
        assertThat(store.state(rolled[0]).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT name FROM sys_dict_type WHERE code=?", String.class, type)).isEqualTo("committed");
        jdbc.update("DELETE FROM sys_dict_type WHERE code=?", type);
    }

    @Test void twoInstancesWithOneHundredReadyClaimersLeaseEachMessageOnce() throws Exception {
        var namespace = namespace(); var first = store(namespace); var second = store(namespace);
        for (int i = 0; i < 20; i++) enqueue(first);
        var ready = new CountDownLatch(100); var start = new CountDownLatch(1);
        var ids = new java.util.HashSet<Long>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                ready.countDown(); start.await(); return (i % 2 == 0 ? first : second).claim("CACHE_INVALIDATE");
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var task : tasks) { var lease = task.get(10, TimeUnit.SECONDS); if (lease != null) assertThat(ids.add(lease.id())).isTrue(); }
        }
        assertThat(ids).hasSize(20);
        assertThat(first.claim("CACHE_INVALIDATE")).isNull();
    }

    @Test void expiredLeaseIsFencedAndTheNextWorkerCommitsExactlyOnce() {
        var store = store(namespace()); long id = enqueue(store); var first = store.claim("CACHE_INVALIDATE");
        jdbc.update("UPDATE sys_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", id);
        assertThat(store.renew(first)).isFalse(); assertThat(store.reap()).isEqualTo(1);
        assertThat(store.state(id).status()).isEqualTo("RETRY_WAIT");
        jdbc.update("UPDATE sys_outbox SET available_at=clock_timestamp() WHERE id=?", id);
        var second = store.claim("CACHE_INVALIDATE");
        assertThat(second.token()).isNotEqualTo(first.token());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> store.complete(first, calls::incrementAndGet)).isInstanceOf(JobFailure.class);
        store.complete(second, calls::incrementAndGet);
        assertThat(calls).hasValue(1); assertThat(store.state(id).status()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> store.complete(second, calls::incrementAndGet)).isInstanceOf(JobFailure.class);
    }

    @Test void fiveFailuresBackoffThenDeadLetterAndExplicitRetryStartsAFreshAttempt() {
        var store = store(namespace()); long id = enqueue(store);
        for (int attempt = 1; attempt <= 5; attempt++) {
            var lease = store.claim("CACHE_INVALIDATE"); assertThat(lease.attempt()).isEqualTo(attempt);
            store.fail(lease, new JobFailure(JobFailure.Code.TEMPORARY_FAILURE, false));
            assertThat(store.state(id).status()).isEqualTo(attempt == 5 ? "DEAD_LETTER" : "RETRY_WAIT");
            assertThat(store.claim("CACHE_INVALIDATE")).isNull();
            if (attempt < 5) jdbc.update("UPDATE sys_outbox SET available_at=clock_timestamp() WHERE id=?", id);
        }
        store.retry(id); var retried = store.claim("CACHE_INVALIDATE"); assertThat(retried.attempt()).isEqualTo(1);
        store.complete(retried, () -> {}); assertThat(store.state(id).status()).isEqualTo("SUCCEEDED");
    }

    @Test void queuedAndActiveCancellationCannotPublishAndHousekeepingHasOneOwner() {
        var namespace = namespace(); var first = store(namespace); var second = store(namespace);
        long queued = enqueue(first); first.cancel(queued);
        assertThat(first.claim("CACHE_INVALIDATE")).isNull(); assertThat(first.state(queued).status()).isEqualTo("CANCELLED");
        long active = enqueue(first); var lease = first.claim("CACHE_INVALIDATE"); first.cancel(active);
        assertThatThrownBy(() -> first.check(lease)).isInstanceOf(JobFailure.class).hasMessage("CANCELLED");
        assertThatThrownBy(() -> first.complete(lease, () -> { throw new AssertionError("cancelled effect"); })).isInstanceOf(JobFailure.class);
        first.fail(lease, new JobFailure(JobFailure.Code.CANCELLED, true));
        assertThat(first.state(active).status()).isEqualTo("CANCELLED");
        assertThat(first.housekeepingLease("test-cleanup", 60)).isTrue();
        assertThat(second.housekeepingLease("test-cleanup", 60)).isFalse();
    }
}
