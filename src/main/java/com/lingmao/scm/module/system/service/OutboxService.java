package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.OutboxState;

public interface OutboxService {
    long enqueue(String kind, String dedupeKey, String payload, Long actor, String requestId);
    OutboxState state(long id);
    void cancel(long id);
    void retry(long id);
    /** 一个小的清理租约；返回 false 说明这一轮归别的实例。 */
    boolean housekeepingLease(String name, int seconds);
}
