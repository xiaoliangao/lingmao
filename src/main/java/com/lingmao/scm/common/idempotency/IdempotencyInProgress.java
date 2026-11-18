package com.lingmao.scm.common.idempotency;

/** 相同动作正在另一个数据库事务内执行，客户端保持原键及请求体再查。 */
public final class IdempotencyInProgress extends RuntimeException {
    public IdempotencyInProgress() {
        super("该操作正在处理中，请稍后使用原请求重试");
    }
}
