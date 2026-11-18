package com.lingmao.scm.common.jobs;

/** 每个实现一次只处理一批；调度器给整个部署发一个 60 秒的租约。 */
public interface HousekeepingTask {
    String name();
    default void run() { runUntil(() -> Thread.currentThread().isInterrupted()); }
    void runUntil(java.util.function.BooleanSupplier stopping);
}
