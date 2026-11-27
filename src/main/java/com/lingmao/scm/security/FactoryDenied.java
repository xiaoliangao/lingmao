package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;

/** 工厂账号（RFC 019 §7）调了没标 @FactoryAccess 的接口：回 HTTP 403（AuthFailureAdvice） */
public final class FactoryDenied extends BizException {
    public FactoryDenied(String message) { super(403, message); }
}
