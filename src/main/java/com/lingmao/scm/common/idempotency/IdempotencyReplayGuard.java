package com.lingmao.scm.common.idempotency;

import tools.jackson.databind.JsonNode;

/** 控制器提供当前对象级授权，命中缓存也必须执行，不能只检查 token。 */
public abstract class IdempotencyReplayGuard {
    public abstract void authorizeReplay(String operation, Object[] arguments, JsonNode result);
}
