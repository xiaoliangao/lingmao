package com.lingmao.scm.module.biz.service;

/**
 * 单据公共接口的"这个人能不能看这张单"（DocCommonService）：不存在或不在数据范围里抛 404。
 * 由单据所在的域给，一般就是它 Service 上的一个方法引用：
 * <pre>
 * public void checkVisible(long id) {          // OrderServiceImpl
 *     Order o = getEntity(id);                 // 不存在 → 404
 *     DataScope.current(false).check(o.getCreateBy(), o.getSalesId(), o.getMerchId());   // 不在范围里 → 404
 * }
 * </pre>
 */
@FunctionalInterface
public interface DocGuard {

    void check(long id);
}
