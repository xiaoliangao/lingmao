package com.lingmao.scm.common.jobs;

public interface OutboxWork {
    long id();
    String leaseToken();
    String requestId();
    int attempt();
    /** Check cancellation, fencing and shutdown between bounded batches, never while holding a long business transaction. */
    void checkpoint();
    /** The short final business update and message acknowledgement commit together under the fenced row lock. */
    void complete(Runnable businessUpdate);
}
