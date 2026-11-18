package com.lingmao.scm.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A4LogTest {
    @Test void truncatedQuotedValuesEscapesAndOpaqueHeadersCannotLeakTail() {
        String tail = "Opaque-Secret-Tail-9384";
        for (String input : new String[]{"{\"password\":\"start\\\""+tail+"\"}",
                "Authorization: Bearer " + tail, "Cookie: first=opaque; second="+tail,
                "appSecret=\"" + (" "+tail).repeat(900)}) {
            assertThat(SafeLog.text(input)).contains("[REDACTED]").doesNotContain(tail);
        }
    }

    @Test void everyLogFieldIsByteBoundedAndNumericLookingRequestIdStaysExact() {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.ERROR); event.setLoggerName("汉\\\"".repeat(10_000));
        event.setThreadName("汉\\\"".repeat(10_000)); event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of("traceId", "trace-20261118001234567"));
        event.setMessage("汉\\\"".repeat(20_000));
        var error = new IllegalStateException("never print this");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("C", "m", "汉".repeat(40_000), 1)});
        event.setThrowableProxy(new ThrowableProxy(error));
        String line = new SafeLogLayout().doLayout(event);
        assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(16_384);
        assertThat(JsonMapper.builder().build().readTree(line).get("requestId").asText()).isEqualTo("trace-20261118001234567");
        assertThat(SafeLog.text("汉".repeat(5000)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test void cyclicEmptyExceptionCausesAreBounded() {
        var first = new IllegalStateException(); var second = new IllegalArgumentException();
        first.setStackTrace(new StackTraceElement[0]); second.setStackTrace(new StackTraceElement[0]);
        first.initCause(second); second.initCause(first);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> SafeLog.failure(first));
        var event = new LoggingEvent(); event.setLevel(Level.ERROR); event.setLoggerName("test"); event.setThreadName("test");
        event.setMDCPropertyMap(Map.of()); event.setMessage("failure"); event.setThrowableProxy(new ThrowableProxy(first));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> new SafeLogLayout().doLayout(event));
    }
    @Test void globalLayoutMasksEveryNamedSecretAndBoundsPayloadAndThrowableMessages() {
        String marker = "Sensitive-Alpha-Zeta-9374";
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.ERROR); event.setLoggerName("test.logger"); event.setThreadName("test-thread");
        event.setTimeStamp(System.currentTimeMillis()); event.setMDCPropertyMap(Map.of("traceId", "a4-request-123"));
        event.setMessage("password="+marker+" appSecret="+marker+" token="+marker+" phone=13800138000 "
                + "taxId="+marker+" bankAccount=6222021234567890123 Authorization=Bearer-"+marker+" " + "x".repeat(40_000));
        event.setThrowableProxy(new ThrowableProxy(new java.sql.SQLException("SELECT password FROM private_table WHERE value='"+marker+"'")));
        String line = new SafeLogLayout().doLayout(event);
        assertThat(line).contains("[REDACTED]", "truncated", "a4-request-123", "SQLException")
                .doesNotContain(marker, "13800138000", "6222021234567890123", "private_table", "SELECT password");
        assertThat(line.length()).isLessThan(16_384);
        assertThat(JsonMapper.builder().build().readTree(line).get("requestId").asText()).isEqualTo("a4-request-123");
    }

    @Test void validUpstreamIdIsPreservedAndMalformedOrLongValuesAreReplaced() throws Exception {
        var filter = new TraceIdFilter(JsonMapper.builder().build());
        for (String supplied : new String[]{"upstream-request-139", "bad\r\nheader", "x".repeat(65), "short"}) {
            var request = new MockHttpServletRequest("GET","/api/version"); request.addHeader("X-Request-Id",supplied);
            var response = new MockHttpServletResponse(); filter.doFilter(request,response,(a,b)->{});
            String id = response.getHeader("X-Request-Id");
            assertThat(id).isEqualTo(response.getHeader("X-Trace-Id"));
            if (supplied.startsWith("upstream")) assertThat(id).isEqualTo(supplied);
            else assertThat(id).matches("[0-9a-f-]{36}");
        }
        var legacy = new MockHttpServletRequest("GET", "/api/version");
        legacy.addHeader("X-Trace-Id", "trace-20261118001234567");
        var response = new MockHttpServletResponse(); filter.doFilter(legacy, response, (a,b) -> {});
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("trace-20261118001234567");
    }
}
