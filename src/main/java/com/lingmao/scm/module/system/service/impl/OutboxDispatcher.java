package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import com.lingmao.scm.common.lifecycle.DrainParticipant;
import com.lingmao.scm.common.lifecycle.HaltStrategy;
import com.lingmao.scm.common.mybatis.JdbcNetworkDeadline;
import com.lingmao.scm.common.log.AuditContext;
import com.lingmao.scm.common.log.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 两个导出工作线程、单独的短清缓存任务，每 10 秒续一次租约。 */
@Component
public final class OutboxDispatcher implements SmartLifecycle, DrainParticipant {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore store;
    private final Map<String, OutboxHandler> handlers;
    private final boolean enabled;
    private final List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping;
    private final HaltStrategy halt;
    private final Duration stopTimeout;
    private final Map<Long, Work> active = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private ScheduledExecutorService scheduler;
    private java.util.concurrent.ExecutorService workers;
    private ScheduledFuture<?> pollSchedule, cleanSchedule, heartbeatSchedule;
    private volatile boolean running;
    private volatile boolean accepting;
    private volatile boolean forced;
    private final java.util.concurrent.atomic.AtomicInteger controls = new java.util.concurrent.atomic.AtomicInteger();
    private final Object admission = new Object();

    public OutboxDispatcher(OutboxStore store, List<OutboxHandler> handlers,
                            List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping,
                            boolean enabled) {
        this(store, handlers, housekeeping, enabled, status -> Runtime.getRuntime().halt(status));
    }

    @Autowired public OutboxDispatcher(OutboxStore store, List<OutboxHandler> handlers,
                            List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping,
                            @Value("${lingmao.jobs.enabled:true}") boolean enabled, HaltStrategy halt) {
        this(store, handlers, housekeeping, enabled, halt, Duration.ofSeconds(15));
    }

    OutboxDispatcher(OutboxStore store, List<OutboxHandler> handlers,
                     List<com.lingmao.scm.common.jobs.HousekeepingTask> housekeeping,
                     boolean enabled, HaltStrategy halt, Duration stopTimeout) {
        this.store = store; this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::kind, h -> h)); this.enabled = enabled;
        this.housekeeping = List.copyOf(housekeeping);
        if (stopTimeout.isNegative() || stopTimeout.isZero() || stopTimeout.compareTo(Duration.ofSeconds(15)) > 0)
            throw new IllegalArgumentException("Invalid worker stop deadline");
        this.halt = halt; this.stopTimeout = stopTimeout;
    }

    @Override public synchronized void start() {
        if (!enabled || running) return;
        running = true; accepting = true; forced = false;
        var control = new ScheduledThreadPoolExecutor(2, Thread.ofPlatform().daemon().name("outbox-control-", 0).uncaughtExceptionHandler(this::uncaught).factory());
        control.setRemoveOnCancelPolicy(true);
        control.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        control.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler = control;
        workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("outbox-work-", 0).uncaughtExceptionHandler(this::uncaught).factory());
        pollSchedule = scheduler.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        heartbeatSchedule = scheduler.scheduleWithFixedDelay(this::heartbeat, 10, 10, TimeUnit.SECONDS);
        cleanSchedule = scheduler.scheduleWithFixedDelay(this::clean, 20, 60, TimeUnit.SECONDS);
    }

    private void poll() {
        if (!accepting) return;
        controls.incrementAndGet();
        try (var ignored = JdbcNetworkDeadline.open(5000)) {
          synchronized (admission) {
            if (!accepting) return;
            store.reap();
            for (int i = 0; i < 4 && accepting && handlers.containsKey("CACHE_INVALIDATE"); i++) {
                var lease = store.claim("CACHE_INVALIDATE"); if (lease == null) break; execute(lease);
            }
            // 钉钉消息（RFC 019 §4）：要调外网，和导出一样放到 worker 上，不卡轮询（缓存失效还要一秒一轮）；一天几十条，一秒一条够了
            if (accepting && handlers.containsKey("DINGTALK_MESSAGE") && slots.tryAcquire()) {
                boolean submitted = false;
                try {
                    var lease = store.claim("DINGTALK_MESSAGE");
                    if (lease != null) { workers.execute(new SubmittedWork(lease)); submitted = true; }
                } finally { if (!submitted) slots.release(); }
            }
            if (accepting && handlers.containsKey("STOCK_LOG_EXPORT") && slots.tryAcquire()) {
                boolean submitted = false;
                try {
                    var lease = store.claim("STOCK_LOG_EXPORT");
                    if (lease != null) { workers.execute(new SubmittedWork(lease)); submitted = true; }
                } finally { if (!submitted) slots.release(); }
            }
          }
        } catch (RuntimeException failure) {
            log.warn("Outbox polling temporarily unavailable: {}", failure.getClass().getSimpleName());
        } catch (Error fatal) { uncaught(Thread.currentThread(), fatal); }
        finally { controls.decrementAndGet(); }
    }

    private void clean() {
        if (!accepting) return;
        controls.incrementAndGet();
        try (var ignored = JdbcNetworkDeadline.open(5000)) {
        for (var task : housekeeping) {
            if (!accepting) return;
            try { if (store.housekeepingLease(task.name(), 60)) task.runUntil(() -> !accepting || Thread.currentThread().isInterrupted()); }
            catch (RuntimeException failure) { log.warn("Housekeeping {} temporarily unavailable: {}", task.name(), failure.getClass().getSimpleName()); }
        }
        } catch (Error fatal) { uncaught(Thread.currentThread(), fatal); }
        finally { controls.decrementAndGet(); }
    }

    private void heartbeat() {
        if (!running || forced) return;
        controls.incrementAndGet();
        try (var ignored = JdbcNetworkDeadline.open(5000)) {
        for (Work work : active.values()) {
            if (forced || Thread.currentThread().isInterrupted()) return;
            try { if (!store.renew(work.lease)) work.lost.set(true); }
            catch (RuntimeException unavailable) { work.lost.set(true); }
        }
        } catch (Error fatal) { uncaught(Thread.currentThread(), fatal); }
        finally { controls.decrementAndGet(); }
    }

    private void execute(OutboxStore.Lease lease) {
        Work work = new Work(lease); active.put(lease.id(), work);
        String previousTrace = MDC.get(TraceIdFilter.MDC_KEY);
        MDC.put(TraceIdFilter.MDC_KEY, TraceIdFilter.normalized(lease.requestId()));
        try (var network = JdbcNetworkDeadline.open(5000);
             var ignored = AuditContext.open(lease.actor() == null ? "" : lease.actor().toString(), "worker",
                MDC.get(TraceIdFilter.MDC_KEY), "OUTBOX " + lease.kind())) {
            log.info("outbox_started id={} kind={} attempt={}", lease.id(), lease.kind(), lease.attempt());
            work.checkpoint(); handlers.get(lease.kind()).execute(work, lease.payload());
            if (!work.completed) work.complete(() -> {});
            log.info("outbox_completed id={} attempt={}", lease.id(), lease.attempt());
        } catch (Exception failure) {
            JobFailure safe = failure instanceof JobFailure known ? known : new JobFailure(JobFailure.Code.TEMPORARY_FAILURE, false);
            // 强制停机时把持久租约留给下一个实例去回收。
            // 这里再发一次 JDBC 往返，会超出本来就用完的停机时间。
            failIfOwned(lease, safe);
            log.warn("Outbox task {} attempt {} ended: {}", lease.id(), lease.attempt(), safe.code());
        } catch (Error fatal) {
            failIfOwned(lease, new JobFailure(JobFailure.Code.TEMPORARY_FAILURE, false));
            uncaught(Thread.currentThread(), fatal);
        } finally {
            active.remove(lease.id(), work);
            if (previousTrace == null) MDC.remove(TraceIdFilter.MDC_KEY); else MDC.put(TraceIdFilter.MDC_KEY, previousTrace);
        }
    }

    private void failIfOwned(OutboxStore.Lease lease, JobFailure failure) {
        if (forced) return;
        try (var ignored = JdbcNetworkDeadline.open(5000)) { store.fail(lease, failure); }
        catch (RuntimeException unavailable) { /* The durable lease reaper will recover it. */ }
    }

    private final class SubmittedWork implements Runnable {
        private final OutboxStore.Lease lease;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private SubmittedWork(OutboxStore.Lease lease) { this.lease = lease; }
        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { execute(lease); } finally { slots.release(); }
        }
        void cancelledBeforeStart() { if (claimed.compareAndSet(false, true)) slots.release(); }
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
            if (forced || Thread.currentThread().isInterrupted()) throw new JobFailure(JobFailure.Code.SHUTDOWN, false);
            if (lost.get()) throw new JobFailure(JobFailure.Code.LEASE_LOST, false);
            store.check(lease);
        }
        @Override public void complete(Runnable businessUpdate) { checkpoint(); store.complete(lease, businessUpdate); completed = true; }
    }

    private void uncaught(Thread thread, Throwable failure) {
        forced = true; accepting = false;
        var handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler != null) handler.uncaughtException(thread, failure);
        else log.error("outbox_uncaught type={}", failure.getClass().getName());
    }
    @Override public void beginDrain() {
        accepting = false;
        if (pollSchedule != null) pollSchedule.cancel(false);
        if (cleanSchedule != null) cleanSchedule.cancel(false);
    }
    @Override public boolean drained() { return active.isEmpty() && controls.get() == 0 && slots.availablePermits() == 2; }
    @Override public void forceStop() {
        forced = true; accepting = false;
        if (workers != null) for (Runnable pending : workers.shutdownNow())
            if (pending instanceof SubmittedWork work) work.cancelledBeforeStart();
        if (scheduler != null) scheduler.shutdownNow();
    }
    @Override public synchronized void stop() {
        if (!running) return;
        beginDrain(); workers.shutdown();
        long deadline = System.nanoTime() + stopTimeout.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            try { workers.awaitTermination(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(3), deadline - System.nanoTime())), TimeUnit.NANOSECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
            if (!workers.isTerminated()) forceStop();
            // 现在没有工作线程要续租（或者 forceStop 已经收回本机的所有权）。
            if (heartbeatSchedule != null) heartbeatSchedule.cancel(false);
            scheduler.shutdownNow();
            for (var executor : List.of(workers, scheduler)) {
                while (!executor.isTerminated() && System.nanoTime() < deadline) {
                    try { executor.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; forceStop(); }
                }
            }
            if (!workers.isTerminated() || !scheduler.isTerminated()) {
                log.error("outbox_stop_deadline_exceeded remaining={} controls={} workersTerminated={} controlsTerminated={}; halting before database destruction",
                        active.size(), controls.get(), workers.isTerminated(), scheduler.isTerminated());
                halt.halt(1);
                throw new IllegalStateException("Process halt unexpectedly returned");
            }
            running = false;
            log.info("outbox_stopped remaining={} controls={} workersTerminated=true controlsTerminated=true", active.size(), controls.get());
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    // 和应用最后的拒绝请求不同，这个可以重启：start() 新建线程池、重置准入。
    // 跟着 Redis 等依赖一起暂停，不在它们停的时候还在消费。
    @Override public boolean isPauseable() { return true; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2000; }
}
