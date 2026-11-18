package com.lingmao.scm.security;

import com.lingmao.scm.common.exception.BizException;

public final class FieldPermissionDenied extends BizException {
    public FieldPermissionDenied(String label) { super(403, "没有" + label + "权限，请交由获授权人员处理"); }
}
