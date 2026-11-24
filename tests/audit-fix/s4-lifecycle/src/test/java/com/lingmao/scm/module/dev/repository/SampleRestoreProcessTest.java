package com.lingmao.scm.module.dev.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure process/privacy tests: no PostgreSQL connection or fabricated successful restore. */
class SampleRestoreProcessTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test
    void unknownOutputAndUntrustedEventFieldsAreHashedWithoutLeakingTheirBytes() throws Exception {
        String safe = "{\"protocol\":\"s4-restore-1\",\"kind\":\"command\",\"stage\":\"pg_restore\",\"phase\":\"failure\",\"exitCode\":7}\n";
        String privateText = "password=PRIVATE_SENTINEL\n"
                + "{\"protocol\":\"s4-restore-1\",\"kind\":\"command\",\"stage\":\"pg_restore\",\"phase\":\"failure\",\"password\":\"PRIVATE_SENTINEL\"}\n"
                + "{\"protocol\":\"s4-restore-1\",\"kind\":\"command\",\"stage\":\"PRIVATE_SENTINEL\",\"phase\":\"failure\"}\n"
                + "{\"protocol\":\"s4-restore-1\",\"kind\":\"result\",\"stage\":\"result\",\"phase\":\"complete\"}\n";
        byte[] input = (privateText + safe).getBytes(StandardCharsets.UTF_8);
        var capture = new SampleRestoreProcess.Capture(true, json);
        capture.accept(input, input.length); capture.finish();
        Map<String, Object> result = capture.snapshot();
        assertThat(result.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
        assertThat(result.get("unknownLines")).isEqualTo(4L);
        assertThat(result.get("retainedEventCount")).isEqualTo(1);
        assertThat(result.get("terminalEventSeen")).isEqualTo(false);
        assertThat(json.writeValueAsString(result)).doesNotContain("PRIVATE_SENTINEL", "password=");
        assertThat(((Map<?, ?>) result.get("firstFailedCommand")).get("exitCode")).isEqualTo(7);
    }

    @Test
    void captureLimitsNeverStopDrainingAndHashingTheRemainingOutput() throws Exception {
        String event = "{\"protocol\":\"s4-restore-1\",\"kind\":\"command\",\"stage\":\"pg_restore\",\"phase\":\"complete\"}\n";
        byte[] input = (event.repeat(SampleRestoreProcess.MAX_EVENTS + 2)
                + "PRIVATE_SENTINEL".repeat(SampleRestoreProcess.MAX_PARSE_BYTES / 8) + "\n").getBytes(StandardCharsets.UTF_8);
        var capture = new SampleRestoreProcess.Capture(true, json);
        for (int offset = 0; offset < input.length; offset += 997) {
            byte[] part = java.util.Arrays.copyOfRange(input, offset, Math.min(input.length, offset + 997));
            capture.accept(part, part.length);
        }
        capture.finish();
        Map<String, Object> result = capture.snapshot();
        assertThat(result.get("bytes")).isEqualTo((long) input.length);
        assertThat(result.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
        assertThat(result.get("complete")).isEqualTo(true);
        assertThat(result.get("captureLimitReached")).isEqualTo(true);
        assertThat(((Number) result.get("retainedEventCount")).intValue()).isLessThanOrEqualTo(SampleRestoreProcess.MAX_EVENTS);
        assertThat(((Number) result.get("retainedEventBytes")).intValue()).isLessThanOrEqualTo(SampleRestoreProcess.MAX_EVENT_BYTES);
        assertThat(((List<?>) result.get("events")).size()).isLessThanOrEqualTo(SampleRestoreProcess.MAX_EVENTS);
        assertThat(json.writeValueAsString(result)).doesNotContain("PRIVATE_SENTINEL");
    }

    @Test
    void actualConcurrentPipesRetainPrimaryExitEvenWhenParentReportCannotBeWritten() throws Exception {
        String script = """
                import json,sys
                sys.stderr.write('PRIVATE_SENTINEL\\n'*20000);sys.stderr.flush()
                print(json.dumps({'protocol':'s4-restore-1','kind':'command','stage':'pg_restore','phase':'failure','exitCode':7}),flush=True)
                print(json.dumps({'protocol':'s4-restore-1','kind':'result','stage':'result','phase':'failure','exitCode':1,
                    'passed':False,'verificationPassed':False,'evidenceWritten':True,'primaryFailure':True,
                    'cleanupFailed':False,'targetDatabaseRemoved':True,'privateArchiveRemoved':True}),flush=True)
                raise SystemExit(1)
                """;
        Path report = temporary.resolve("safe-process.json");
        var ordinary = SampleRestoreProcess.run(new ProcessBuilder("python3", "-c", script), report, json);
        assertThat(ordinary.finished()).isTrue();
        assertThat(ordinary.exitCode()).isEqualTo(1);
        assertThat(ordinary.diagnosticsComplete()).isTrue();
        assertThat(Files.readString(report)).doesNotContain("PRIVATE_SENTINEL");
        assertThat(ordinary.safeSummary()).contains("pg_restore", "firstFailedCommand").doesNotContain("PRIVATE_SENTINEL");

        // A separate controlled failure: writing a report to a directory must not
        // replace the helper's original nonzero exit with a Files.write exception.
        Path unwritable = Files.createDirectory(temporary.resolve("directory-not-file"));
        var failedWrite = SampleRestoreProcess.run(new ProcessBuilder("python3", "-c", script), unwritable, json);
        assertThat(failedWrite.finished()).isTrue();
        assertThat(failedWrite.exitCode()).isEqualTo(1);
        assertThat(failedWrite.diagnosticsComplete()).isFalse();
        assertThat(failedWrite.safeSummary()).contains("reportWriteFailure", "pg_restore").doesNotContain("PRIVATE_SENTINEL");
    }
}
