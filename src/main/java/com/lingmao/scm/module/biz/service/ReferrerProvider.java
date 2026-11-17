package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 引用查询（架构 §4.4、§3.3 第 3 种）：每个业务域实现一个（一个 @Component 就行），报告"本域里有哪些单据引用了 (type, id)"。
 * ReferenceService 把所有实现收集起来，上游域不用知道下游有哪些域：反审核前检查、取消订单前检查、拉黑供应商时列出未完成单据、
 * "下游单据"页签、变更提醒发给谁，都走它。主数据"用过的只能停用"不靠它，靠外键（架构 §6）。
 * <pre>
 * &#64;Component
 * &#64;RequiredArgsConstructor
 * class PurchaseReferrers implements ReferrerProvider {        // purchase 域：采购单引用了算料单
 *     public List&lt;DocRef&gt; referrers(BizType type, long id) {
 *         if (type != BizType.MRP_CALC) return List.of();          // 不归我管的类型返回空
 *         return poMapper.selectByMrpId(id).stream()               // 作废的不算
 *                 .map(po -&gt; DocRef.of(BizType.PURCHASE_ORDER, po.getId(), po.getNo(), po.getStatus(), PO_STATUS, po.getBuyerId()))
 *                 .toList();
 *     }
 *     public Map&lt;Long, DocRef&gt; describe(BizType type, Collection&lt;Long&gt; ids) {   // 本域自己的单据
 *         if (type != BizType.PURCHASE_ORDER) return Map.of();
 *         …
 *     }
 * }
 * </pre>
 * 单独写一个类，不要让单据的 ServiceImpl 自己实现：ReferenceService 要收集所有实现，单据的 Service 又要调 ReferenceService，
 * 写在一起就成了循环依赖（ReferenceServiceImpl 用 ObjectProvider 晚取，能绕开，但分开更好读）。
 * 状态的中文、颜色由实现方翻好（DocRef.of + StatusLabels），负责人的昵称由 ReferenceService 统一填，这里只给 ownerId
 */
public interface ReferrerProvider {

    /** 本域里引用了 (type, id) 的单据，作废的不算；不归我管的类型返回空列表 */
    List<DocRef> referrers(BizType type, long id);

    /**
     * 本域自己的单据，id → DocRef：变更提醒的来源、首页"上游已变更"、待办要显示单号和状态时用。
     * 不是本域的类型返回空 map；查不到的 id 不在 map 里。只有被别人引用、自己没有单据的域不用实现
     */
    default Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        return Map.of();
    }
}
