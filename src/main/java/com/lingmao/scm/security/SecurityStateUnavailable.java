package com.lingmao.scm.security;

/** 共享的安全判断做不出来；调用方不能在没有结果时继续。 */
public final class SecurityStateUnavailable extends RuntimeException {
    public SecurityStateUnavailable(Throwable cause) { super("安全验证暂不可用，请稍后重试", cause); }
}
