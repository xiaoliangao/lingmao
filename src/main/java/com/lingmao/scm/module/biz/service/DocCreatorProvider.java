package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.entity.BizType;

import java.util.Collection;
import java.util.Map;

/**
 * 多级审批的单据告诉审批服务制单人是谁（{@link SelfAudit}：自己建的单不能自己审）。需求订单、采购单、生产单各实现一个
 * （一个 @Component，和 ReferrerProvider 一样单独写一个类，不让单据的 ServiceImpl 自己实现：ApprovalService 收集所有实现，
 * 单据的 Service 又依赖 ApprovalService，会绕成一圈）。
 */
public interface DocCreatorProvider {

    /** 管哪种单据：ORDER、PURCHASE_ORDER、PRODUCTION_ORDER 之一 */
    BizType type();

    /** 单据 id → 制单人；查不到的、没有制单人的不放进来。ids 是空的返回空 */
    Map<Long, Long> creators(Collection<Long> ids);
}
