package com.lingmao.scm.common.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;

/** The only console/file layout: structured bounded lines and stack locations without exception messages. */
public final class SafeLogLayout extends LayoutBase<ILoggingEvent> {
    @Override public String doLayout(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(512);
        String requestId = event.getMDCPropertyMap().get(TraceIdFilter.MDC_KEY);
        out.append("{\"time\":").append(SafeLog.quote(Instant.ofEpochMilli(event.getTimeStamp()).toString()))
                .append(",\"level\":").append(SafeLog.quote(event.getLevel().toString()))
                .append(",\"thread\":").append(SafeLog.quote(SafeLog.text(event.getThreadName(), 192)))
                .append(",\"logger\":").append(SafeLog.quote(SafeLog.text(event.getLoggerName(), 256)))
                .append(",\"requestId\":").append(SafeLog.quote(TraceIdFilter.isValid(requestId) ? requestId : ""))
                .append(",\"message\":").append(SafeLog.quote(SafeLog.text(event.getFormattedMessage())));
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            out.append(",\"error\":").append(SafeLog.quote(SafeLog.text(error.getClassName(), 256)));
            StringBuilder locations = new StringBuilder();
            int remaining = 16, depth = 0;
            for (IThrowableProxy cause = error; cause != null && remaining > 0 && depth++ < 4; cause = cause.getCause()) {
                locations.append(SafeLog.text(cause.getClassName(), 256)).append(':');
                var frames = cause.getStackTraceElementProxyArray();
                if (frames != null) for (var frame : frames) {
                    if (remaining-- <= 0) break;
                    locations.append(SafeLog.text(frame.getStackTraceElement().toString(), 256)).append(';');
                }
            }
            out.append(",\"stack\":").append(SafeLog.quote(SafeLog.text(locations.toString(), 2048)));
        }
        return out.append("}\n").toString();
    }
}
