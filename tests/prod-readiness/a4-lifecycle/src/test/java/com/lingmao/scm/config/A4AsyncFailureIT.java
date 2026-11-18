package com.lingmao.scm.config;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.ScmApplication;
import com.lingmao.scm.common.lifecycle.DrainState;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import javax.sql.DataSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Own the real context outside Spring's test cache: this test intentionally closes it. */
@ExtendWith(OutputCaptureExtension.class)
class A4AsyncFailureIT {
    @Test void uncaughtWorkerFailureRefusesTrafficAndClosesResources(CapturedOutput output) throws Exception {
        var app=new SpringApplication(ScmApplication.class,IntegrationTestConfig.class);
        app.setAdditionalProfiles("it");app.setRegisterShutdownHook(false);
        try(var context=app.run("--server.port=0","--lingmao.shutdown.drain-timeout=2s")) {
            HikariDataSource pool=context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            DrainState state=context.getBean(DrainState.class);
            assertThat(state.ready()).isTrue();assertThat(pool.isClosed()).isFalse();
            Thread worker=Thread.ofPlatform().name("a4-uncaught-test").start(()->{
                throw new IllegalStateException("password=A4-Never-Print-This-9374");
            });
            worker.join(2000);
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while((!pool.isClosed() || context.isActive()) && System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(state.refusing()).isTrue();assertThat(context.isActive()).isFalse();assertThat(pool.isClosed()).isTrue();
            assertThat(output.getAll()).contains("uncaught_async_failure","shutdown_refusing_traffic","shutdown_drain_complete")
                    .doesNotContain("A4-Never-Print-This-9374");
        }
    }
}
