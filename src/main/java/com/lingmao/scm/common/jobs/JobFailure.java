package com.lingmao.scm.common.jobs;

/** 只把这个安全的枚举告诉客户端；异常、SQL、文件路径都不作为任务错误返回。 */
public final class JobFailure extends RuntimeException {
    public enum Code { CANCELLED, LEASE_LOST, LEASE_EXPIRED, PERMISSION_REVOKED, ROW_LIMIT, SIZE_LIMIT, DEADLINE,
        DISK_FULL, FILE_MISSING, INVALID_INPUT, SHUTDOWN, TEMPORARY_FAILURE }
    private final Code code;
    private final boolean permanent;
    public JobFailure(Code code, boolean permanent) { super(code.name()); this.code = code; this.permanent = permanent; }
    public Code code() { return code; }
    public boolean permanent() { return permanent; }
}
