package com.lingmao.scm.common.jobs;

/** Only this safe enumeration is exposed to clients; exceptions/SQL/file paths are never returned as job errors. */
public final class JobFailure extends RuntimeException {
    public enum Code { CANCELLED, LEASE_LOST, LEASE_EXPIRED, PERMISSION_REVOKED, ROW_LIMIT, SIZE_LIMIT, DEADLINE,
        DISK_FULL, FILE_MISSING, INVALID_INPUT, SHUTDOWN, TEMPORARY_FAILURE }
    private final Code code;
    private final boolean permanent;
    public JobFailure(Code code, boolean permanent) { super(code.name()); this.code = code; this.permanent = permanent; }
    public Code code() { return code; }
    public boolean permanent() { return permanent; }
}
