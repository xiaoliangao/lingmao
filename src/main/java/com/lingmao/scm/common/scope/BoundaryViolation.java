package com.lingmao.scm.common.scope;

import com.lingmao.scm.common.exception.BizException;

public final class BoundaryViolation extends BizException {
    public BoundaryViolation(int code, String message) { super(code, message); }
}
