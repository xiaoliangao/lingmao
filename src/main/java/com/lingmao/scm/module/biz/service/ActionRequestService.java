package com.lingmao.scm.module.biz.service;

import java.util.UUID;
import java.util.function.Supplier;

/** 新增业务动作的事务内防重（RFC010 §3）；调用方先做本次接口/对象授权。 */
public interface ActionRequestService {
    <T> T execute(String operation, long targetId, UUID clientActionId, Object request, Class<T> resultType, Supplier<T> action);
}
