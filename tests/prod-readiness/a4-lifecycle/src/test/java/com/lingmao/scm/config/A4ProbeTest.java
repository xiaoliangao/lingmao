package com.lingmao.scm.config;

import com.lingmao.scm.common.lifecycle.DrainState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class A4ProbeTest {
    @Test @Timeout(8)
    void timedOutJdbcRetainsItsSlotAndRepeatedProbesCannotQueue() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var connections = new AtomicInteger();
        when(source.getConnection()).thenAnswer(call -> {
            if (connections.incrementAndGet() == 1) { entered.countDown(); uninterruptibly(release); }
            return connection;
        });
        DrainState state = new DrainState(); state.initialized();
        var probes = new ProbeConfiguration.Probes(state, source, mock(Flyway.class), status -> { throw new AssertionError("unexpected halt"); });
        try {
            long began = System.nanoTime();
            assertThat(probes.ready().getStatusCode().value()).isEqualTo(503);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(3));
            try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                var calls = java.util.stream.IntStream.range(0, 100).mapToObj(i -> clients.submit(probes::ready)).toList();
                for (var call : calls) assertThat(call.get(1, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(503);
            }
            assertThat(connections).hasValue(1);
            var executor = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(probes, "executor");
            assertThat(executor).isNotNull(); assertThat(executor.getQueue()).isEmpty();
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (probes.ready().getStatusCode().value() != 200) {
                assertThat(System.nanoTime()).isLessThan(deadline); Thread.sleep(10);
            }
            verify(connection, atLeastOnce()).setNetworkTimeout(any(), eq(1000));
            verify(statement, atLeastOnce()).setQueryTimeout(1);
        } finally { release.countDown(); probes.close(); }
        assertThat(probes.drained()).isTrue();
    }

    @Test @Timeout(5)
    void closeWaitsForTheActualIgnoredInterruptCallToLeave() throws Exception {
        DataSource source = mock(DataSource.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(source.getConnection()).thenAnswer(call -> { entered.countDown(); uninterruptibly(release); throw new java.sql.SQLException(); });
        DrainState state = new DrainState(); state.initialized();
        var probes = new ProbeConfiguration.Probes(state, source, mock(Flyway.class), status -> { throw new AssertionError("unexpected halt"); });
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = clients.submit(probes::ready);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            state.refuse(); probes.beginDrain(); probes.forceStop();
            var closing = clients.submit(probes::close);
            Thread.sleep(100);
            assertThat(probes.drained()).isFalse(); assertThat(closing.isDone()).isFalse();
            assertThat(probes.ready().getStatusCode().value()).isEqualTo(503);
            release.countDown(); closing.get(2, TimeUnit.SECONDS); request.get(2, TimeUnit.SECONDS);
            assertThat(probes.drained()).isTrue();
        } finally { release.countDown(); probes.close(); }
    }

    private static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
