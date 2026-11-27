package com.lingmao.scm.common.jobs;

/** 只把这个安全的枚举告诉客户端；异常、SQL、文件路径都不作为任务错误返回。 */
public final class JobFailure extends RuntimeException {
    public enum Code { CANCELLED, LEASE_LOST, LEASE_EXPIRED, PERMISSION_REVOKED, ROW_LIMIT, SIZE_LIMIT, DEADLINE,
        DISK_FULL, FILE_MISSING, INVALID_INPUT, SHUTDOWN, TEMPORARY_FAILURE,
        /** 钉钉消息（RFC 019 §4）：钉钉没配置、收件人找不到（没手机号、钉钉里没这个人、停用了）、钉钉拒收（看日志里的 errcode） */
        NOT_CONFIGURED, NO_RECIPIENT, REMOTE_REJECTED }
    private final Code code;
    private final boolean permanent;
    public JobFailure(Code code, boolean permanent) { super(code.name()); this.code = code; this.permanent = permanent; }
    public Code code() { return code; }
    public boolean permanent() { return permanent; }
}
