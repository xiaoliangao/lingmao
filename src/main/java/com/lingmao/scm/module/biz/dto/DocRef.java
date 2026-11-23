package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;

/**
 * 指向一张单据（架构 §4.4、docs/api/README §4.5，前端 types/biz.ts 的 DocRef）："下游单据"页签、1005 的清单（反审核、取消、删除被拦住）、
 * 首页待办、变更提醒的来源都是它。前端点单号用 utils/docRoute.ts 按 type、kind、parentId 找详情页。
 * <p>
 * 状态的中文和颜色在这里就翻好（statusLabel、statusTone）：中文按单据叫，只有那张单据所在的域知道，
 * 所以由实现 ReferrerProvider 的域用 {@link #of} 造，ReferenceService 再统一填负责人的昵称（ownerName）。
 * <pre>
 * DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), p.getStatus(), PRODUCTION_STATUS, p.getMerchId())
 *         .withKind(p.getKind().name());                     // 一张表两种单据的带上 kind，typeLabel 跟着换成"外发加工单"
 * DocRef.of(BizType.PRODUCTION_ISSUE, …).withParent(issue.getOrderId());   // 挂在上一级页面里的（回货单、异常、资料包）
 * </pre>
 *
 * @param kind       一张表两种单据的：PRODUCTION_ORDER 是 OUTSOURCE / BUY，INV_DOC 是 IN / OUT；别的 null
 * @param parentId   没有自己详情页、挂在上一级页面里的：回货单、异常 → 生产单 id，资料包 → 款式 id；别的 null
 * @param typeLabel  外发加工单、物料入库单……（按 kind 取好的）
 * @param status     状态的英文名
 * @param ownerId    负责人（跟单、采购员、制单人，看单据），没有是 null
 * @param ownerName  负责人的昵称，ReferenceService 填
 */
public record DocRef(BizType type,
                     String kind,
                     Long parentId,
                     String typeLabel,
                     long id,
                     String no,
                     String status,
                     String statusLabel,
                     StatusTone statusTone,
                     Long ownerId,
                     String ownerName,
                     Long companyId) {

    public DocRef(BizType type, String kind, Long parentId, String typeLabel, long id, String no, String status,
                  String statusLabel, StatusTone statusTone, Long ownerId, String ownerName) {
        this(type, kind, parentId, typeLabel, id, no, status, statusLabel, statusTone, ownerId, ownerName, null);
    }

    /** 造一个：状态按 labels 翻成这种单据的叫法和颜色；kind、parentId、负责人昵称先空着 */
    public static <S extends Enum<S>> DocRef of(BizType type, long id, String no, S status, StatusLabels<S> labels, Long ownerId) {
        StatusLabels.Label label = labels.label(status);
        return new DocRef(type, null, null, type.getLabel(), id, no, status.name(), label.text(), label.tone(), ownerId, null);
    }

    /** 一张表两种单据的：带上 kind，typeLabel 换成这一种的叫法（BizType.label(kind)） */
    public DocRef withKind(String kind) {
        return new DocRef(type, kind, parentId, type.label(kind), id, no, status, statusLabel, statusTone, ownerId, ownerName, companyId);
    }

    /** 挂在上一级页面里的：上一级的 id */
    public DocRef withParent(Long parentId) {
        return new DocRef(type, kind, parentId, typeLabel, id, no, status, statusLabel, statusTone, ownerId, ownerName, companyId);
    }

    public DocRef withCompany(Long companyId) {
        return new DocRef(type, kind, parentId, typeLabel, id, no, status, statusLabel, statusTone, ownerId, ownerName, companyId);
    }

    public DocRef withOwnerName(String ownerName) {
        return new DocRef(type, kind, parentId, typeLabel, id, no, status, statusLabel, statusTone, ownerId, ownerName, companyId);
    }
}
