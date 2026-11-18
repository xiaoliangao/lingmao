package com.lingmao.scm.config;

import com.lingmao.scm.common.lifecycle.DrainParticipant;
import com.lingmao.scm.common.lifecycle.DrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 停机第一阶段保持 HTTP 连接器开着：就绪探针先报失败，再在限定时间内等请求做完。 */
@Component
public class GracefulLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GracefulLifecycle.class);
    private final DrainState state; private final List<DrainParticipant> participants; private final Duration timeout;
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean running;
    private Thread.UncaughtExceptionHandler previous;

    public GracefulLifecycle(DrainState state, List<DrainParticipant> participants,
            @Value("${lingmao.shutdown.drain-timeout:25s}") Duration timeout, ConfigurableApplicationContext context) {
        if (timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(25)) > 0)
            throw new IllegalArgumentException("drain-timeout must be within 0..25s");
        this.state = state; this.participants = List.copyOf(participants); this.timeout = timeout; this.context = context;
    }
    @Override public void start() {
        running = true;
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            log.error("uncaught_async_failure thread={} type={}; refusing traffic", thread.getName(), failure.getClass().getName());
            state.refuse();
            if (closing.compareAndSet(false, true)) Thread.ofPlatform().name("controlled-shutdown").start(context::close);
        });
    }
    @EventListener @Order(Ordered.HIGHEST_PRECEDENCE)
    public void closing(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) return;
        state.refuse();
        log.info("shutdown_refusing_traffic inFlight={}", state.inFlight());
        participants.forEach(DrainParticipant::beginDrain);
    }
    @Override public void stop() {
        if (!running) return;
        state.refuse(); participants.forEach(DrainParticipant::beginDrain);
        long deadline = System.nanoTime() + timeout.toNanos();
        while ((state.inFlight() > 0 || participants.stream().anyMatch(p -> !p.drained())) && System.nanoTime() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        boolean completed = state.inFlight() == 0 && participants.stream().allMatch(DrainParticipant::drained);
        if (!completed) participants.forEach(DrainParticipant::forceStop);
        log.info("shutdown_drain_complete completed={} inFlight={}", completed, state.inFlight());
        running = false;
        Thread.setDefaultUncaughtExceptionHandler(previous);
    }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    // Spring 7 会暂停缓存的、在用的上下文而不关闭。拒绝新请求、等参与者收尾都不能撤回，
    // 只能在真正关闭上下文时做。
    @Override public boolean isPauseable() { return false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
