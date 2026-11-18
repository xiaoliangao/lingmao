package com.lingmao.scm.config;

import com.lingmao.scm.common.lifecycle.DrainState;
import com.lingmao.scm.common.lifecycle.DrainParticipant;
import com.lingmao.scm.common.lifecycle.HaltStrategy;
import com.lingmao.scm.common.mybatis.JdbcNetworkDeadline;
import com.lingmao.scm.common.result.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods=false)
public class ProbeConfiguration {
    @Bean HaltStrategy processHaltStrategy() { return status -> Runtime.getRuntime().halt(status); }
    @Bean FilterRegistrationBean<OncePerRequestFilter> drainFilter(DrainState state, JsonMapper json) {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getRequestURI().startsWith("/health/")) { chain.doFilter(request, response); return; }
                if (!state.enter()) {
                    response.setStatus(503); response.setHeader("Retry-After", "3");
                    response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getWriter(), R.fail(503, "系统正在升级，请稍后重试")); return;
                }
                try { chain.doFilter(request, response); } finally { state.leave(); }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }

    @RestController
    public static class Probes implements AutoCloseable, DrainParticipant {
        private final DrainState state; private final DataSource dataSource; private final Flyway flyway;
        private final HaltStrategy halt;
        private final Semaphore probe = new Semaphore(1);
        // No waiting tasks: a timed-out JDBC call retains the only slot until it actually exits.
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("readiness-probe").factory(),
                new ThreadPoolExecutor.AbortPolicy());
        public Probes(DrainState state, DataSource dataSource, Flyway flyway) {
            this(state, dataSource, flyway, status -> Runtime.getRuntime().halt(status));
        }
        @Autowired public Probes(DrainState state, DataSource dataSource, Flyway flyway, HaltStrategy halt) {
            this.state = state; this.dataSource = dataSource; this.flyway = flyway; this.halt = halt;
        }
        @EventListener public void initialized(ApplicationReadyEvent ignored) {
            if (!flyway.validateWithResult().validationSuccessful || flyway.info().pending().length != 0)
                throw new IllegalStateException("Database migration readiness check failed");
            state.initialized();
        }
        @GetMapping("/health/live") public Map<String,String> live() { return Map.of("status", "UP"); }
        @GetMapping("/health/ready") public ResponseEntity<Map<String,String>> ready() {
            if (!state.ready() || !probe.tryAcquire()) return unavailable();
            var task = new FutureTask<Boolean>(this::databaseReady);
            try {
                executor.execute(() -> { try { task.run(); } finally { probe.release(); } });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                probe.release(); return unavailable();
            }
            try {
                return task.get(1500, TimeUnit.MILLISECONDS) && state.ready()
                        ? ResponseEntity.ok(Map.of("status", "UP")) : unavailable();
            } catch (InterruptedException interrupted) {
                task.cancel(true); Thread.currentThread().interrupt(); return unavailable();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
                task.cancel(true); return unavailable();
            }
        }
        private boolean databaseReady() {
            try (var ignored = JdbcNetworkDeadline.open(1000); var connection = dataSource.getConnection()) {
                connection.setNetworkTimeout(Runnable::run, 1000);
                try (var query = connection.prepareStatement("SELECT 1")) {
                    query.setQueryTimeout(1);
                    try (var result = query.executeQuery()) {
                        return result.next() && result.getInt(1) == 1;
                    }
                }
            } catch (Exception unavailable) { return false; }
        }
        @Override public void beginDrain() { executor.shutdown(); }
        @Override public boolean drained() { return executor.isTerminated(); }
        @Override public void forceStop() { executor.shutdownNow(); }
        @Override public void close() {
            forceStop(); boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            try {
                while (!executor.isTerminated() && System.nanoTime() < deadline) {
                    try { executor.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (!executor.isTerminated()) {
                    org.slf4j.LoggerFactory.getLogger(Probes.class).error("readiness_stop_deadline_exceeded terminated=false; halting before database destruction");
                    halt.halt(1);
                    throw new IllegalStateException("Process halt unexpectedly returned");
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private ResponseEntity<Map<String,String>> unavailable() { return ResponseEntity.status(503).body(Map.of("status", "NOT_READY")); }
    }
}
