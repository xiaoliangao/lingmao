package com.lingmao.scm.common.log;

import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/** All scans run on a bounded prefix; no log payload or stack message is trusted. */
public final class SafeLog {
    public static final int LIMIT = 4096;
    private static final String TRUNCATED = "…[truncated]";
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)[\\\"']?([A-Za-z_-]{0,80}password|passwd|[A-Za-z_-]{0,80}token|authorization|cookie|set-cookie|"
                    + "[A-Za-z_-]{0,80}secret|app[_-]?key|phone|mobile|telephone|credit[_-]?code|tax[_-]?(?:id|number|no)|"
                    + "bank[_-]?(?:account|card|number)(?:[_-]?name)?)[\\\"']?\\s*[:=]\\s*");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer [A-Za-z0-9._~+/-]{1,8192}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{1,1024}");
    private static final Pattern NUMBERS = Pattern.compile("(?<![0-9])(?:1[3-9][0-9]{9}|[0-9]{16,19})(?![0-9])");
    private SafeLog() { }

    public static String failure(Throwable failure) {
        StringBuilder result = new StringBuilder(failure.getClass().getName());
        int remaining = 12, depth = 0;
        for (Throwable cause = failure; cause != null && remaining > 0 && depth++ < 4; cause = cause.getCause()) {
            result.append(" causedBy=").append(text(cause.getClass().getName(), 256));
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (remaining-- <= 0) break;
                result.append(" at ").append(text(frame.toString(), 256));
            }
        }
        return text(result.toString());
    }

    public static String text(String value) {
        return text(value, LIMIT);
    }

    public static String text(String value, int byteLimit) {
        if (value == null) return "";
        boolean cut = value.length() > 8192;
        String safe = value.substring(0, Math.min(value.length(), 8192));
        safe = redactValues(safe);
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = JWT.matcher(safe).replaceAll("[REDACTED JWT]");
        safe = NUMBERS.matcher(safe).replaceAll("[REDACTED NUMBER]");
        int end = utf8End(safe, byteLimit);
        if (!cut && end == safe.length()) return safe;
        return safe.substring(0, utf8End(safe, byteLimit - TRUNCATED.getBytes(StandardCharsets.UTF_8).length)) + TRUNCATED;
    }

    /** A bounded linear scan also masks quoted values cut off before their closing quote. */
    private static String redactValues(String value) {
        var keys = SECRET_KEY.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        int cursor = 0;
        while (keys.find()) {
            int start = keys.end(), end = start;
            if (start < value.length() && (value.charAt(start) == '\"' || value.charAt(start) == '\'')) {
                char quote = value.charAt(start); end++;
                while (end < value.length()) {
                    char next = value.charAt(end++);
                    if (next == '\\' && end < value.length()) end++;
                    else if (next == quote) break;
                }
            } else {
                String key = keys.group(1);
                boolean header = key.equalsIgnoreCase("authorization") || key.toLowerCase(java.util.Locale.ROOT).endsWith("cookie");
                while (end < value.length()) {
                    char next = value.charAt(end);
                    if (header ? next == '\r' || next == '\n' : Character.isWhitespace(next) || next == ',' || next == ';' || next == '}') break;
                    end++;
                }
            }
            out.append(value, cursor, start).append("[REDACTED]");
            cursor = end;
            keys.region(end, value.length());
        }
        return out.append(value, cursor, value.length()).toString();
    }

    private static int utf8End(String value, int byteLimit) {
        int index = 0, bytes = 0;
        while (index < value.length()) {
            int point = value.codePointAt(index);
            int length = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (bytes + length > byteLimit) break;
            bytes += length; index += Character.charCount(point);
        }
        return index;
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append('?'); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
