package com.lingmao.scm.module.biz.service;

import java.util.UUID;
import java.util.function.Supplier;

/** 新增业务动作的事务内防重（RFC010 §3）；调用方先做本次接口/对象授权。 */
public interface ActionRequestService {
    <T> T execute(String operation, long targetId, UUID clientActionId, Object request, Class<T> resultType, Supplier<T> action);

    /**
     * 当前用户带这个提交编号（Idempotency-Key / clientActionId）的提交是不是已经成功。
     * 前端网络中断后用它核对：true 就是已保存；false 是没保存或者还没到服务器，前端过一会儿再定（docs/api/README §2）
     */
    boolean completed(UUID key);
}
