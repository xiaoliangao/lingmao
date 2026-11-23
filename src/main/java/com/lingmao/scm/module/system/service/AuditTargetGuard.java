package com.lingmao.scm.module.system.service;

import java.util.Set;

/** 由对象所在的域校验审计读取权限，system 不反向依赖具体业务域。 */
public interface AuditTargetGuard {
    Set<String> tables();
    void check(String table, long id);
}
