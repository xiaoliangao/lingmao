package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Two bounded export workers, separate short cache jobs and ten-second lease heartbeats. */
@Component
public final class OutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore store;
    private final Map<String, OutboxHandler> handlers;
    private final boolean enabled;
    private final List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping;
    private final Map<Long, Work> active = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private ScheduledExecutorService scheduler;
    private java.util.concurrent.ExecutorService workers;
    private volatile boolean running;

    public OutboxDispatcher(OutboxStore store, List<OutboxHandler> handlers,
                            List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping,
                            @Value("${lingmao.jobs.enabled:true}") boolean enabled) {
        this.store = store; this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::kind, h -> h)); this.enabled = enabled;
        this.housekeeping = List.copyOf(housekeeping);
    }

    @Override public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("outbox-control-", 0).factory());
        workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("outbox-work-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::heartbeat, 10, 10, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::clean, 20, 60, TimeUnit.SECONDS);
    }

    private void poll() {
        if (!running) return;
        try {
            store.reap();
            for (int i = 0; i < 4 && running && handlers.containsKey("CACHE_INVALIDATE"); i++) {
                var lease = store.claim("CACHE_INVALIDATE"); if (lease == null) break; execute(lease);
            }
            if (handlers.containsKey("STOCK_LOG_EXPORT") && slots.tryAcquire()) {
                boolean submitted = false;
                try {
                    var lease = store.claim("STOCK_LOG_EXPORT");
                    if (lease != null) { workers.execute(() -> { try { execute(lease); } finally { slots.release(); } }); submitted = true; }
                } finally { if (!submitted) slots.release(); }
            }
        } catch (RuntimeException failure) {
            log.warn("Outbox polling temporarily unavailable: {}", failure.getClass().getSimpleName());
        }
    }

    private void clean() {
        for (var task : housekeeping) {
            if (!running) return;
            try { if (store.housekeepingLease(task.name(), 60)) task.run(); }
            catch (RuntimeException failure) { log.warn("Housekeeping {} temporarily unavailable: {}", task.name(), failure.getClass().getSimpleName()); }
        }
    }

    private void heartbeat() {
        for (Work work : active.values()) {
            try { if (!store.renew(work.lease)) work.lost.set(true); }
            catch (RuntimeException unavailable) { work.lost.set(true); }
        }
    }

    private void execute(OutboxStore.Lease lease) {
        Work work = new Work(lease); active.put(lease.id(), work);
        try (var ignored = MDC.putCloseable("traceId", lease.requestId())) {
            work.checkpoint(); handlers.get(lease.kind()).execute(work, lease.payload());
            if (!work.completed) work.complete(() -> {});
        } catch (Exception failure) {
            JobFailure safe = failure instanceof JobFailure known ? known : new JobFailure(JobFailure.Code.TEMPORARY_FAILURE, false);
            try { store.fail(lease, safe); } catch (RuntimeException unavailable) { /* The lease reaper will recover it. */ }
            log.warn("Outbox task {} attempt {} ended: {}", lease.id(), lease.attempt(), safe.code());
        } finally { active.remove(lease.id(), work); }
    }

    private final class Work implements OutboxWork {
        private final OutboxStore.Lease lease;
        private final AtomicBoolean lost = new AtomicBoolean();
        private boolean completed;
        private Work(OutboxStore.Lease lease) { this.lease = lease; }
        @Override public long id() { return lease.id(); }
        @Override public String leaseToken() { return lease.token().toString(); }
        @Override public String requestId() { return lease.requestId(); }
        @Override public int attempt() { return lease.attempt(); }
        @Override public void checkpoint() {
            if (!running || Thread.currentThread().isInterrupted()) throw new JobFailure(JobFailure.Code.SHUTDOWN, false);
            if (lost.get()) throw new JobFailure(JobFailure.Code.LEASE_LOST, false);
            store.check(lease);
        }
        @Override public void complete(Runnable businessUpdate) { checkpoint(); store.complete(lease, businessUpdate); completed = true; }
    }

    @Override public synchronized void stop() {
        if (!running) return;
        running = false; scheduler.shutdownNow(); workers.shutdownNow();
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
