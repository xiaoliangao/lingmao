package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;

public final class AuthRejected extends BizException {
    public AuthRejected() { super(401, "未登录或登录已过期"); }
}
