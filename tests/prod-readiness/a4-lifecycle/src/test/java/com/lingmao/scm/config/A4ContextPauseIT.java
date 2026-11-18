package com.lingmao.scm.config;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.ScmApplication;
import com.lingmao.scm.common.lifecycle.DrainState;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.impl.OutboxDispatcher;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Own a real HTTP context: never change the test cache policy or replace its lifecycle processor. */
@ExtendWith(OutputCaptureExtension.class)
class A4ContextPauseIT {
    @Test @Timeout(60)
    void actualPauseRestartKeepsHttpReadyAndRestartsOutboxUntilFinalClose(CapturedOutput output) throws Exception {
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        var app = new SpringApplication(ScmApplication.class, IntegrationTestConfig.class);
        app.setAdditionalProfiles("it");
        app.setRegisterShutdownHook(false);
        try (var context = app.run("--server.port=0", "--lingmao.jobs.enabled=true", "--lingmao.shutdown.drain-timeout=2s");
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var state = context.getBean(DrainState.class);
            var lifecycle = context.getBean(GracefulLifecycle.class);
            var dispatcher = context.getBean(OutboxDispatcher.class);
            var probes = context.getBean(ProbeConfiguration.Probes.class);
            var pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            var installedHandler = Thread.getDefaultUncaughtExceptionHandler();
            assertThat(lifecycle.isPauseable()).isFalse();
            assertThat(dispatcher.isPauseable()).isTrue();
            assertAvailable(context, http);
            completedRealOutboxJob(context);

            for (int cycle = 0; cycle < 2; cycle++) {
                context.pause();
                assertThat(context.isActive()).isTrue();
                assertThat(lifecycle.isRunning()).isTrue();
                assertThat(state.ready()).isTrue();
                assertThat(state.refusing()).isFalse();
                assertThat(pool.isClosed()).isFalse();
                assertThat(dispatcher.isRunning()).isFalse();
                assertThat(dispatcher.drained()).isTrue();
                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(installedHandler);

                context.restart();
                assertThat(dispatcher.isRunning()).isTrue();
                assertThat(state.refusing()).isFalse();
                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(installedHandler);
                assertAvailable(context, http);
                completedRealOutboxJob(context);
            }
            assertThat(output.getAll()).doesNotContain("shutdown_refusing_traffic", "shutdown_drain_complete");

            int beforeClose = output.getAll().length();
            context.close();
            assertThat(context.isActive()).isFalse();
            assertThat(state.refusing()).isTrue();
            assertThat(state.ready()).isFalse();
            assertThat(state.enter()).isFalse();
            assertThat(state.inFlight()).isZero();
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(dispatcher.isRunning()).isFalse();
            assertThat(dispatcher.drained()).isTrue();
            assertThat(probes.drained()).isTrue();
            assertThat(pool.isClosed()).isTrue();
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(previousHandler);
            String closeLog = output.getAll().substring(beforeClose);
            int refusing = closeLog.indexOf("shutdown_refusing_traffic");
            int drained = closeLog.indexOf("shutdown_drain_complete completed=true");
            int workers = closeLog.indexOf("outbox_stopped remaining=0 controls=0 workersTerminated=true controlsTerminated=true");
            int database = closeLog.indexOf("Shutdown initiated.");
            assertThat(refusing).isGreaterThanOrEqualTo(0);
            assertThat(drained).isGreaterThan(refusing);
            assertThat(workers).isGreaterThan(drained);
            assertThat(database).isGreaterThan(workers);
            assertThat(closeLog).contains("Shutdown completed.");
        }
    }

    private static void assertAvailable(ConfigurableApplicationContext context, HttpClient http) throws Exception {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var json = context.getBean(JsonMapper.class);
        for (String path : new String[]{"/health/live", "/health/ready", "/api/version"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            if (path.equals("/api/version")) assertThat(json.readTree(response.body()).path("code").intValue()).isZero();
            else assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
        }
    }

    private static void completedRealOutboxJob(ConfigurableApplicationContext context) throws Exception {
        var outbox = context.getBean(OutboxService.class);
        var json = context.getBean(JsonMapper.class);
        var namespace = context.getBean(DeploymentNamespace.class);
        String unique = UUID.randomUUID().toString();
        long job = outbox.enqueue("CACHE_INVALIDATE", "a4-pause-" + unique,
                json.writeValueAsString(namespace.sharedKey("dict", "a4-pause-" + unique)), null, "a4-pause-" + unique);
        // Prioritize only this fixture's job; earlier tests can leave unrelated durable jobs in the shared test database.
        context.getBean(JdbcTemplate.class).update("UPDATE sys_outbox SET available_at=clock_timestamp()-interval '1 day' WHERE id=?", job);
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!outbox.state(job).status().equals("SUCCEEDED") && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(outbox.state(job).status()).isEqualTo("SUCCEEDED");
    }
}
