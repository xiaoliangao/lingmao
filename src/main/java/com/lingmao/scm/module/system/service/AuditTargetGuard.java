package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.exception.BizException;

import java.util.Set;

/** 由对象所在的域校验审计读取权限，system 不反向依赖具体业务域。 */
public interface AuditTargetGuard {
    record Root(String table, long id) { }

    /** 由审计服务按真实注册表分派；调用方不能自报“历史对象已授权”。 */
    @FunctionalInterface
    interface HistoricalRootGuard {
        void check(String table, long id);
    }

    Set<String> tables();
    void check(String table, long id);

    /** 非 null 返回值表示本次已验证的唯一业务根，供结果一致性检查和页内去重。 */
    default Root check(String table, long id, HistoricalRootGuard historical) {
        check(table, id);
        return null;
    }

    /** 根查询也可能包含其他域的附属事件；返回前按事件的真实根补做授权。 */
    default void checkEventRoot(Root root, HistoricalRootGuard historical) { }

    /** 只有能证明已删对象真实归属的域才覆盖此方法。 */
    default void checkHistoricalRoot(String table, long id) {
        throw new BizException(404, "审计对象不存在或无权查看");
    }
}
