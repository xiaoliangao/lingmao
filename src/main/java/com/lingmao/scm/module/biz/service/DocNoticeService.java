package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.DocNoticeGroup;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.Collection;
import java.util.List;

/**
 * 上游变更提醒（架构 §4.4、PRD §6.5.6，表 biz_doc_notice）。需求订单的变更单审核时，在同一个事务里给每张受影响的下游单据插一条：
 * <pre>
 * for (DocRef d : referenceService.referrers(BizType.ORDER, orderId)) {          // 变更提醒发给谁：下游单据的负责人
 *     docNoticeService.add(d.type(), d.id(), BizType.ORDER_CHANGE, changeId, "订单 DD… 的 27CF001 黑色 M 码从 100 改成 80", d.ownerId());
 * }
 * </pre>
 * 下游详情顶上的黄条（DocHeaderVO.pendingNotices）、"处理"按钮、首页"上游已变更"都读它。
 * 看、处理提醒不单独开接口：挂在那张单据自己的 /api/&lt;单据&gt;/{id}/notices 下，权限、数据范围跟着单据走（DocCommonService）
 */
public interface DocNoticeService {

    /** 插一条，content 最长 500（超了截断）；ownerId 是插入时下游单据的负责人，可以是 null */
    void add(BizType docType, long docId, BizType sourceType, long sourceId, String content, Long ownerId);

    /** 一张单据的全部提醒：没处理的在前，同一组里新的在前；source 翻成 DocRef */
    List<DocNoticeVO> list(BizType docType, long docId);

    /**
     * 处理一条：记下谁、什么时候、怎么处理的（remark 选填，最长 200）。提醒不是这张单的 → 404；
     * 已经被别人处理了 → 1002（条件更新 handled_time IS NULL，两个人同时点只有一个成功）
     */
    void handle(BizType docType, long docId, long noticeId, String remark);

    /** 没处理的条数：详情的 pendingNotices */
    int countPending(BizType docType, long docId);

    /**
     * 首页"上游已变更"（06-首页 §2.5）：没处理的提醒按单据分组，一张单一组，组里新的在前，组按最新一条倒序。
     * docTypes 限定哪几种单据；ownerId 是 null 时所有人的（有"全部"范围的人看全部时）
     */
    List<DocNoticeGroup> pendingByDoc(Collection<BizType> docTypes, Long ownerId);
}
