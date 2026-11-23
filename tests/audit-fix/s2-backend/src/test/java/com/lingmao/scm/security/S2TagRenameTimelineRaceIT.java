package com.lingmao.scm.security;

import com.lingmao.scm.module.biz.entity.Tag;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** True service/Repository/HTTP interleaving. The aspect pauses a completed SQL read, never returns mocked data. */
@Import(S2TagRenameTimelineRaceIT.RaceConfig.class)
class S2TagRenameTimelineRaceIT extends A2BusinessFixture {
    @Autowired RenameGate gate;

    @Test void futureExpectedVersionCannotMakeTimelineUseAnOlderPredecessorThanDatabaseDiff() throws Exception {
        Fixture fixture = fixture();
        Account manager = custom(fixture.company(), "ALL", "biz:tag:list", "biz:tag:manage", "style:style:edit", "style:style:list");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String original = "rename-original-" + suffix, middle = "rename-middle-" + suffix, last = "rename-last-" + suffix;
        JsonNode object = ok(manager, "POST", "/api/tags/objects/STYLE/" + fixture.style() + "/replace",
                Map.of("version", 0, "names", List.of(original), "tagVersions", Map.of(), "retainIds", List.of()), null);
        long tag = id(object.path("tags").get(0).path("id"));
        String pausedTrace = "tag-future-" + suffix, competitorTrace = "tag-middle-" + suffix;
        gate.configure(tag, pausedTrace, competitorTrace);
        JsonNode firstReply, secondReply;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> rename(manager, tag, 1, last, pausedTrace));
            assertThat(gate.firstRead.await(8, TimeUnit.SECONDS)).as("first actual SELECT returned version 0").isTrue();
            secondReply = rename(manager, tag, 0, middle, competitorTrace);
            try {
                assertThat(secondReply.path("code").intValue()).isZero();
                assertThat(jdbc.queryForObject("SELECT version FROM biz_tag WHERE id=?", Integer.class, tag)).isEqualTo(1);
            } finally { gate.resume.countDown(); }
            firstReply = first.get(12, TimeUnit.SECONDS);
        } finally { gate.resume.countDown(); }
        var tagRow = jdbc.queryForMap("SELECT id,name,version FROM biz_tag WHERE id=?", tag);
        var fieldDiffs = jdbc.queryForList("SELECT row_id,request_id,diff::text AS diff FROM sys_data_log WHERE table_name='biz_tag' AND row_id=? AND action='UPDATE' ORDER BY id", tag);
        var timeline = jdbc.queryForList("SELECT id,request_id,remark FROM biz_doc_log WHERE doc_type='PUBLIC_TAG' AND doc_id=? AND action='rename' ORDER BY id", tag);
        Path directory = Path.of("target/a2-tags-review"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("rename-timeline-interleaving.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                map("firstRequest", Map.of("version", 1, "name", last, "requestId", pausedTrace),
                        "competitorRequest", Map.of("version", 0, "name", middle, "requestId", competitorTrace),
                        "actualFirstReadSnapshots", gate.actualReads, "actualReadEvents", gate.readEvents, "actualHttpStatuses", gate.httpStatuses, "firstHttpResponse", firstReply, "competitorHttpResponse", secondReply,
                        "actualFinalTag", tagRow, "actualDatabaseDiffs", fieldDiffs, "actualTimeline", timeline)));
        // Old service succeeds with v2 while its timeline says original -> last; this assertion is the genuine red control.
        assertThat(gate.actualReads.get(pausedTrace).get("version")).isEqualTo(0);
        assertThat(gate.actualReads.get(pausedTrace).get("backendPid")).isNotEqualTo(gate.actualReads.get(competitorTrace).get("backendPid"));
        assertThat(firstReply.path("code").intValue()).isEqualTo(1002);
        assertThat(tagRow.get("name")).isEqualTo(middle); assertThat(tagRow.get("version")).isEqualTo(1);
        assertThat(fieldDiffs).hasSize(1); assertThat(timeline).hasSize(1);
        JsonNode diff = json.readTree(fieldDiffs.getFirst().get("diff").toString());
        assertThat(diff.path("name").path("old").stringValue()).isEqualTo(original);
        assertThat(diff.path("name").path("new").stringValue()).isEqualTo(middle);
        assertThat(timeline.getFirst().get("remark").toString()).contains(original, middle).doesNotContain(last);
    }

    private JsonNode rename(Account actor, long tag, int expected, String name, String trace) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tags/" + tag + "/rename"))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + actor.token()).header("X-Request-Id", trace)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("version", expected, "name", name)))).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        JsonNode result = node(response);
        gate.httpStatuses.put(trace, response.statusCode());
        return result;
    }

    static final class RenameGate {
        volatile long tag;
        volatile String pausedTrace;
        volatile String competitorTrace;
        final CountDownLatch firstRead = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicBoolean paused = new AtomicBoolean();
        final Map<String, Map<String,Object>> actualReads = new ConcurrentHashMap<>();
        final Map<String,Integer> httpStatuses = new ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentLinkedQueue<Map<String,Object>> readEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicLong eventSequence = new java.util.concurrent.atomic.AtomicLong();
        void configure(long id, String paused, String competitor) { tag=id; pausedTrace=paused; competitorTrace=competitor; }
    }
    @Aspect
    static final class ReadPause {
        private final RenameGate gate;
        private final JdbcTemplate jdbc;
        ReadPause(RenameGate gate, JdbcTemplate jdbc) { this.gate=gate;this.jdbc=jdbc; }
        @Around("execution(* com.lingmao.scm.module.biz.repository.TagRepository.get(..))")
        Object afterRealRead(ProceedingJoinPoint call) throws Throwable {
            String trace = MDC.get("traceId");
            boolean recorded = gate.tag > 0 && ((Long) call.getArgs()[0]) == gate.tag
                    && (java.util.Objects.equals(trace, gate.pausedTrace)
                    || java.util.Objects.equals(trace, gate.competitorTrace));
            if (recorded) {
                // Scope SQL logging to these already authenticated business transactions; bind values are not logged.
                jdbc.execute("SET LOCAL log_parameter_max_length=0");
                jdbc.execute("SET LOCAL log_statement='all'");
            }
            Tag value = (Tag) call.proceed();
            if (value.id() == gate.tag && (java.util.Objects.equals(trace, gate.pausedTrace) || java.util.Objects.equals(trace, gate.competitorTrace))) {
                Map<String,Object> snapshot = Map.of("id", Long.toString(value.id()), "name", value.name(), "version", value.version(),
                        "backendPid", jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                gate.actualReads.putIfAbsent(trace, snapshot);
                var event = new java.util.LinkedHashMap<String,Object>(snapshot);
                event.put("requestId", trace); event.put("sequence", gate.eventSequence.incrementAndGet());
                gate.readEvents.add(event);
                if (java.util.Objects.equals(trace, gate.pausedTrace) && gate.paused.compareAndSet(false, true)) {
                    gate.firstRead.countDown();
                    if (!gate.resume.await(12, TimeUnit.SECONDS)) throw new AssertionError("competing actual HTTP rename did not finish");
                }
            }
            return value;
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class RaceConfig {
        @Bean RenameGate renameGate() { return new RenameGate(); }
        @Bean ReadPause readPause(RenameGate gate, JdbcTemplate jdbc) { return new ReadPause(gate, jdbc); }
    }
}
