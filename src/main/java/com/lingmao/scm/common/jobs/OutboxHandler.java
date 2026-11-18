package com.lingmao.scm.common.jobs;

/** 业务处理器由各自的模块注册，调度器不反向依赖业务域。 */
public interface OutboxHandler {
    String kind();
    void execute(OutboxWork work, String payload) throws Exception;
}
