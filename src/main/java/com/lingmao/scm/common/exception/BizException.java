package com.lingmao.scm.common.exception;

import lombok.Getter;

/**
 * 业务异常：Service 层发现"业务上不允许"的情况时抛它，比如编码重复、库存不足、状态不允许审核。
 * 为什么继承 RuntimeException：不用在每个方法签名上声明 throws，Spring 的事务也只对运行时异常默认回滚。
 * 它不是程序错误，所以全局异常处理器拿到它只记 warn、不打堆栈，直接把 msg 原样返回给前端。
 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(String msg) {
        this(1000, msg);
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
