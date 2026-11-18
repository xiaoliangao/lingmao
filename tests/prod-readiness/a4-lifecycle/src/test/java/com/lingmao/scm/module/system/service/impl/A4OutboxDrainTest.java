package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class A4OutboxDrainTest {
    @Test @Timeout(8)
    void heartbeatStillInJdbcPreventsDrainedAndStopWaitsForItsRealExit(CapturedOutput output) throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        var lease = lease(); when(store.claim("STOCK_LOG_EXPORT")).thenReturn(lease, (OutboxStore.Lease) null);
        var workEntered = new CountDownLatch(1); var workRelease = new CountDownLatch(1);
        var renewEntered = new CountDownLatch(1); var renewRelease = new CountDownLatch(1);
        when(store.renew(lease)).thenAnswer(call -> { renewEntered.countDown(); uninterruptibly(renewRelease); return true; });
        var dispatcher = new OutboxDispatcher(store, List.of(handler(workEntered, workRelease)), List.of(), true,
                status -> { throw new AssertionError("unexpected halt"); });
        dispatcher.start();
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            ReflectionTestUtils.invokeMethod(dispatcher, "poll");
            assertThat(workEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(dispatcher, "scheduler");
            assertThat(scheduler).isNotNull();
            scheduler.execute(() -> ReflectionTestUtils.invokeMethod(dispatcher, "heartbeat"));
            assertThat(renewEntered.await(1, TimeUnit.SECONDS)).isTrue();
            workRelease.countDown();
            waitUntil(() -> ((Map<?,?>) ReflectionTestUtils.getField(dispatcher, "active")).isEmpty());
            dispatcher.beginDrain();
            assertThat(dispatcher.drained()).isFalse();
            var stopping = clients.submit(() -> dispatcher.stop());
            Thread.sleep(100); assertThat(stopping.isDone()).isFalse();
            renewRelease.countDown(); stopping.get(2, TimeUnit.SECONDS);
            assertThat(dispatcher.drained()).isTrue(); assertThat(dispatcher.isRunning()).isFalse();
            assertThat(scheduler.isTerminated()).isTrue();
            assertThat(output.getAll()).contains("outbox_stopped remaining=0 controls=0 workersTerminated=true controlsTerminated=true");
        } finally { workRelease.countDown(); renewRelease.countDown(); dispatcher.stop(); }
    }

    @Test @Timeout(5)
    void hardDeadlineUsesInjectedHaltWithoutClaimingTheWorkerTerminated(CapturedOutput output) throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        when(store.claim("STOCK_LOG_EXPORT")).thenReturn(lease(), (OutboxStore.Lease) null);
        var workEntered = new CountDownLatch(1); var workRelease = new CountDownLatch(1); var halts = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(store, List.of(handler(workEntered, workRelease)), List.of(), true,
                status -> { assertThat(status).isEqualTo(1); halts.incrementAndGet(); throw new Halted(); }, Duration.ofMillis(200));
        dispatcher.start();
        try {
            ReflectionTestUtils.invokeMethod(dispatcher, "poll");
            assertThat(workEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(dispatcher::stop).isInstanceOf(Halted.class);
            assertThat(halts).hasValue(1);
            assertThat(output.getAll()).contains("outbox_stop_deadline_exceeded", "workersTerminated=false")
                    .doesNotContain("outbox_stopped remaining=");
        } finally {
            workRelease.countDown(); waitUntil(dispatcher::drained); dispatcher.stop();
        }
    }

    private static OutboxStore.Lease lease() {
        return new OutboxStore.Lease(42, "STOCK_LOG_EXPORT", "{}", UUID.randomUUID(), "a4-drain-test", 1, null);
    }
    private static OutboxHandler handler(CountDownLatch entered, CountDownLatch release) {
        return new OutboxHandler() {
            @Override public String kind() { return "STOCK_LOG_EXPORT"; }
            @Override public void execute(OutboxWork work, String payload) { entered.countDown(); uninterruptibly(release); }
        };
    }
    private static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) { assertThat(System.nanoTime()).isLessThan(deadline); Thread.sleep(10); }
    }
    private static final class Halted extends RuntimeException { }
}
