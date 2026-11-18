package com.lingmao.scm.common.jobs;

/** Each implementation handles a bounded batch; the dispatcher grants one sixty-second deployment lease. */
public interface HousekeepingTask {
    String name();
    default void run() { runUntil(() -> Thread.currentThread().isInterrupted()); }
    void runUntil(java.util.function.BooleanSupplier stopping);
}
