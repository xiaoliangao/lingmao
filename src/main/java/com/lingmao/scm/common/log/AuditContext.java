package com.lingmao.scm.common.log;

/** Authenticated actor data travels with a borrowed database connection, never with client JSON. */
public record AuditContext(String actor, String ip, String requestId, String operation) {
    private static final ThreadLocal<AuditContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> REPLACEMENTS = new ThreadLocal<>();
    public static String replacements() { return REPLACEMENTS.get() == null ? "" : REPLACEMENTS.get(); }
    public static Scope replacements(String value) {
        String previous = REPLACEMENTS.get();
        if (value.length() > 262144) throw new IllegalArgumentException("Audit replacement identity budget exceeded");
        REPLACEMENTS.set(value);
        return () -> { if (previous == null) REPLACEMENTS.remove(); else REPLACEMENTS.set(previous); };
    }
    public static AuditContext current() {
        AuditContext value = CURRENT.get();
        return value == null ? new AuditContext("", "", "", "SYSTEM") : value;
    }
    public static Scope open(String actor, String ip, String requestId, String operation) {
        AuditContext previous = CURRENT.get();
        CURRENT.set(new AuditContext(bounded(actor, 24), bounded(ip, 64), bounded(requestId, 64), bounded(operation, 255)));
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
