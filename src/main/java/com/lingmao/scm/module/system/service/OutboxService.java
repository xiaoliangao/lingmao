package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.OutboxState;

public interface OutboxService {
    long enqueue(String kind, String dedupeKey, String payload, Long actor, String requestId);
    OutboxState state(long id);
    void cancel(long id);
    void retry(long id);
    /** A small housekeeping lease; a false result means another instance owns the current run. */
    boolean housekeepingLease(String name, int seconds);
}
