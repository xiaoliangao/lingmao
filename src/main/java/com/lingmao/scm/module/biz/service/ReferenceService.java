package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 引用查询的入口（架构 §4.4）：把各域的 ReferrerProvider 收齐，按单据问。上游域只调这里，不知道下游有哪些域。
 * <pre>
 * // 反审核、取消、删除前：有下游就 1005，data 是下游单据清单，前端自动弹表格
 * referenceService.assertNoReferrer(BizType.MRP_CALC, id, "反审核");
 * // "下游单据"页签
 * &#64;GetMapping("/{id}/references") → docCommonService.references(BizType.MRP_CALC, id, guard)
 * </pre>
 */
public interface ReferenceService {

    /** 引用了 (type, id) 的单据（作废的不算），带负责人昵称，按类型、id 排 */
    List<DocRef> referrers(BizType type, long id);

    /**
     * 有下游就不让做：1005，msg"已经有下游单据，不能{action}：先处理下面这些单据"，data 是 DocRef 清单。
     * action 写给人看的动作名：反审核、取消、删除
     */
    void assertNoReferrer(BizType type, long id, String action);

    /**
     * 一批单据的 DocRef，id → DocRef（带负责人昵称）：问这种单据所在的域（ReferrerProvider.describe）。
     * 那个域还没写 describe 的，给一个只有类型和 id 的（单号是空的），页面照样能显示、能点
     */
    Map<Long, DocRef> describe(BizType type, Collection<Long> ids);
}
