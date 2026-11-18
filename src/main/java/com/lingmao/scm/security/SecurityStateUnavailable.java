package com.lingmao.scm.security;

/** A shared security decision could not be made; callers must not proceed without it. */
public final class SecurityStateUnavailable extends RuntimeException {
    public SecurityStateUnavailable(Throwable cause) { super("安全验证暂不可用，请稍后重试", cause); }
}
