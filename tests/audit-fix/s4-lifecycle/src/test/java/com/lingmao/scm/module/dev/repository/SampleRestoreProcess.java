package com.lingmao.scm.module.dev.repository;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 子进程原始输出只做哈希/计数；只有严格白名单事件可以进入文件和断言。 */
final class SampleRestoreProcess {
    static final int MAX_LINE_BYTES = 4096;
    static final int MAX_EVENT_BYTES = 64 * 1024;
    static final int MAX_EVENTS = 128;
    static final int MAX_PARSE_BYTES = 256 * 1024;
    private static final Set<String> KINDS = Set.of("command", "verification", "cleanup", "report", "result");
    private static final Set<String> STAGES = Set.of("client_version", "sql_probe", "pg_dump", "create_database", "pg_restore",
            "drop_database", "archive_cleanup", "verification", "preflight", "evidence_write", "result", "command");
    private static final Set<String> PHASES = Set.of("start", "complete", "failure");
    private static final Set<String> CATEGORIES = Set.of("unclassified", "undefined_function", "undefined_relation",
            "check_constraint", "foreign_key_constraint", "unique_constraint", "permission_denied", "statement_timeout",
            "lock_timeout", "disk_full", "io_error");
    private static final Set<String> ERROR_KINDS = Set.of("timeout", "io_error", "verification_failure", "cleanup_failure", "guard_failure");
    private static final Set<String> OBJECTS = Set.of("scm_security_audit_id_valid", "scm_security_audit_link_valid", "scm_sample_audit_identity_valid");
    private static final Set<String> BOOLEANS = Set.of("passed", "verificationPassed", "evidenceWritten", "primaryFailure",
            "cleanupFailed", "targetDatabaseRemoved", "privateArchiveRemoved", "expectedFailure");

    private SampleRestoreProcess() { }

    record Result(boolean finished, int exitCode, boolean diagnosticsComplete, String safeSummary) { }

    static Result run(ProcessBuilder command, Path log, JsonMapper json) throws IOException, InterruptedException {
        command.redirectErrorStream(false).redirectOutput(ProcessBuilder.Redirect.PIPE).redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = command.start();
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(240);
        Capture stdout = new Capture(true, json), stderr = new Capture(false, json);
        CompletableFuture<Void> outDone = drain(process.getInputStream(), stdout, "s4-restore-stdout");
        CompletableFuture<Void> errDone = drain(process.getErrorStream(), stderr, "s4-restore-stderr");
        boolean finished;
        boolean drained = false;
        try {
            finished = process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!finished) terminate(process);
            if (finished) {
                try {
                    CompletableFuture.allOf(outDone, errDone).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    drained = true;
                } catch (TimeoutException ignored) {
                    // Keep the original 240s budget, including pipe consumption.
                } catch (java.util.concurrent.ExecutionException impossible) {
                    // Each drainer records a fixed category and completes its future in finally.
                }
            }
        } catch (InterruptedException primary) {
            try { terminate(process); } catch (InterruptedException cleanup) { primary.addSuppressed(cleanup); }
            Thread.currentThread().interrupt();
            throw primary;
        }
        int exitCode = process.isAlive() ? Integer.MIN_VALUE : process.exitValue();
        Map<String, Object> out = stdout.snapshot(), err = stderr.snapshot();
        Map<?, ?> terminal = out.get("terminalEvent") instanceof Map<?, ?> value ? value : Map.of();
        boolean complete = drained && Boolean.TRUE.equals(out.get("complete")) && Boolean.TRUE.equals(err.get("complete"))
                && !Boolean.TRUE.equals(out.get("captureLimitReached")) && Integer.valueOf(1).equals(out.get("terminalEventCount"))
                && terminal.get("exitCode") instanceof Number code && code.intValue() == exitCode;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("finishedWithin240Seconds", finished);
        report.put("exitCode", exitCode);
        report.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        report.put("stdout", out); report.put("stderr", err);
        report.put("diagnosticsComplete", complete);
        try {
            Files.writeString(log, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        } catch (IOException writeFailure) {
            report.put("reportWriteFailure", "io_error");
            report.put("diagnosticsComplete", false);
            complete = false;
        }
        // All retained events are safe, but failure output only needs the first failed
        // command and terminal status, not a potentially long list of SQL probes.
        Map<String, Object> brief = new LinkedHashMap<>(report);
        brief.put("stdout", stdout.brief()); brief.put("stderr", stderr.brief());
        return new Result(finished, exitCode, complete, json.writeValueAsString(brief));
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy(); // The helper retains its own existing 7s cleanup deadline.
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static CompletableFuture<Void> drain(InputStream input, Capture capture, String name) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofPlatform().name(name).daemon(true).start(() -> {
            try (input) {
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) != -1; ) capture.accept(bytes, count);
                capture.finish();
            } catch (IOException | RuntimeException failure) {
                capture.failed(); // Never retain a raw exception message or stack trace.
            } finally { done.complete(null); }
        });
        return done;
    }

    static final class Capture {
        private final boolean parseEvents;
        private final JsonMapper json;
        private final MessageDigest digest;
        private final byte[] line = new byte[MAX_LINE_BYTES];
        private final List<Map<String, Object>> events = new ArrayList<>();
        private long bytes, lines, lineBytes, unknownBytes, unknownLines;
        private int retainedBytes, terminalEventCount;
        private boolean complete, readFailure, captureLimitReached, terminalEventSeen;
        private Map<String, Object> firstFailedCommand, terminalEvent;

        Capture(boolean parseEvents, JsonMapper json) {
            this.parseEvents = parseEvents; this.json = json;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        synchronized void accept(byte[] value, int count) {
            digest.update(value, 0, count);
            for (int i = 0; i < count; i++) {
                bytes++;
                if (value[i] == '\n') { endLine(true); continue; }
                if (parseEvents && lineBytes < MAX_LINE_BYTES && bytes <= MAX_PARSE_BYTES) line[(int) lineBytes] = value[i];
                lineBytes++;
            }
        }

        private void endLine(boolean newline) {
            lines++;
            Map<String, Object> parsed = null;
            if (parseEvents) {
                if (lineBytes > MAX_LINE_BYTES || bytes > MAX_PARSE_BYTES || events.size() >= MAX_EVENTS) {
                    captureLimitReached = true;
                } else {
                    parsed = event(line, (int) lineBytes, json);
                    if (parsed != null && retainedBytes + lineBytes > MAX_EVENT_BYTES) {
                        captureLimitReached = true; parsed = null;
                    }
                }
            }
            if (parsed == null) {
                unknownLines++; unknownBytes += lineBytes + (newline ? 1 : 0);
            } else {
                retainedBytes += (int) lineBytes;
                events.add(parsed);
                if (firstFailedCommand == null && "command".equals(parsed.get("kind")) && "failure".equals(parsed.get("phase"))) {
                    firstFailedCommand = parsed;
                }
                if ("result".equals(parsed.get("kind"))) {
                    terminalEventSeen = true; terminalEvent = parsed; terminalEventCount++;
                }
            }
            lineBytes = 0;
        }

        synchronized void finish() { if (lineBytes > 0) endLine(false); complete = true; }
        synchronized void failed() { readFailure = true; }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = brief();
            result.put("events", List.copyOf(events));
            return result;
        }

        synchronized Map<String, Object> brief() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bytes", bytes); result.put("lines", lines);
            try { result.put("sha256", HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest())); }
            catch (CloneNotSupportedException impossible) { throw new IllegalStateException("SHA-256 snapshot unavailable"); }
            result.put("unknownBytes", unknownBytes + lineBytes); result.put("unknownLines", unknownLines);
            result.put("complete", complete && !readFailure);
            result.put("readFailure", readFailure);
            result.put("captureLimitReached", captureLimitReached);
            result.put("retainedEventBytes", retainedBytes); result.put("retainedEventCount", events.size());
            result.put("terminalEventSeen", terminalEventSeen);
            result.put("terminalEventCount", terminalEventCount);
            if (terminalEvent != null) result.put("terminalEvent", terminalEvent);
            if (firstFailedCommand != null) result.put("firstFailedCommand", firstFailedCommand);
            if (!events.isEmpty()) result.put("lastEvent", events.getLast());
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> event(byte[] bytes, int length, JsonMapper json) {
        try {
            Object decoded = json.readValue(new String(bytes, 0, length, StandardCharsets.UTF_8), Object.class);
            if (!(decoded instanceof Map<?, ?> raw) || !"s4-restore-1".equals(raw.get("protocol"))
                    || !KINDS.contains(raw.get("kind")) || !STAGES.contains(raw.get("stage")) || !PHASES.contains(raw.get("phase"))) return null;
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) return null;
                Object value = entry.getValue();
                boolean valid = switch (key) {
                    case "protocol" -> "s4-restore-1".equals(value);
                    case "kind" -> KINDS.contains(value);
                    case "stage" -> STAGES.contains(value);
                    case "phase" -> PHASES.contains(value);
                    case "category" -> CATEGORIES.contains(value);
                    case "errorKind" -> ERROR_KINDS.contains(value);
                    case "knownObject" -> OBJECTS.contains(value);
                    case "sqlState" -> value instanceof String s && s.matches("[0-9A-Z]{5}");
                    case "stderrSha256" -> value instanceof String s && s.matches("[0-9a-f]{64}");
                    case "stderrBytes", "elapsedMs" -> integer(value, 0, Long.MAX_VALUE);
                    case "exitCode" -> integer(value, -255, 255);
                    default -> BOOLEANS.contains(key) && value instanceof Boolean;
                };
                if (!valid) return null;
            }
            if ("result".equals(raw.get("kind"))) {
                if (!"result".equals(raw.get("stage")) || !raw.keySet().containsAll(Set.of("exitCode", "passed", "verificationPassed",
                        "evidenceWritten", "primaryFailure", "cleanupFailed", "targetDatabaseRemoved", "privateArchiveRemoved"))) return null;
                long code = ((Number) raw.get("exitCode")).longValue();
                if (code != 0 && code != 1 || !Boolean.valueOf(code == 0).equals(raw.get("passed"))
                        || !(code == 0 ? "complete" : "failure").equals(raw.get("phase"))) return null;
            }
            return Map.copyOf((Map<String, Object>) raw);
        } catch (Exception malformed) { return null; }
    }

    private static boolean integer(Object value, long min, long max) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max;
    }
}
