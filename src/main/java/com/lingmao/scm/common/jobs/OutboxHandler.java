package com.lingmao.scm.common.jobs;

/** Business handlers are registered by their owning module; the dispatcher has no upward domain dependency. */
public interface OutboxHandler {
    String kind();
    void execute(OutboxWork work, String payload) throws Exception;
}
