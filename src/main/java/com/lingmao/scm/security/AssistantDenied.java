package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;

/**
 * 助手令牌（RFC 017 §3.1、§3.2）做了它不能做的事：AI 功能关着、调了非 GET 或返回文件的接口、没有使用助手的权限。
 * 回 HTTP 403（AuthFailureAdvice），过滤器里拦下的也是 403
 */
public final class AssistantDenied extends BizException {
    public AssistantDenied(String message) { super(403, message); }
}
