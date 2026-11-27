package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.dto.PurchasePostingBasis;
import com.lingmao.scm.module.inventory.entity.InvDocType;

import java.util.List;
import java.util.Map;

/**
 * 入库单、出库单的源单那一边（架构 §5.2 "带出、回写源单都用接口反转依赖"）：inventory 不认识采购单、生产单，
 * 由 purchase（采购入库、采购退货）、production（领料出库、退料入库）实现，注册成 Bean。一种源单一个实现。
 * <p>
 * 都在 inventory 的事务里调：建草稿、改草稿时调 {@link #check}；正反过账先 {@link #preparePosting} 再改库存。
 * 来源回写可能锁采购/MRP，不能在库存锁后获取它们；后续库存失败使来源回写一起回滚（RFC010 §2.1）。
 * 抛出的 BizException（1000 源单状态不对、1004 超上限）原样回给前端，整个事务回滚
 */
public interface StockSourceHandler {

    /** 管哪种源单：PURCHASE_ORDER / PRODUCTION_ORDER */
    BizType sourceType();

    /**
     * 带出单头默认值和明细（docs/api/04 §3.3）。源单不存在 404，状态不对 1000。
     *
     * @param itemIds         只带这些源单明细（采购明细 id），给了就带，哪怕已经入满（改草稿时按它核对）；null = 还能入（能退、还差）的全部行
     * @param fromWarehouseId 领料指定的出仓，null = 默认的
     * @param editingDocId    改草稿时是这张草稿自己（"一张源单只能有一张"这类检查不算它），新建 null
     */
    SourceDraft prefill(InvDocType docType, long sourceId, List<Long> itemIds, Long fromWarehouseId, Long editingDocId);

    /** 按源单最新的数查上限：超了抛 1004（data 是 InvOverRow[]），源单状态不让入、不让领抛 1000 */
    void check(SourcePosting posting);

    /** 在库存余额锁之前完成来源校验与回写；reverse=true 为反审核。调用方必须持有库存单头锁。 */
    default void preparePosting(SourcePosting posting, boolean reverse) {
        if (reverse) {
            onUnposted(posting);
        } else {
            check(posting);
            onPosted(posting);
        }
    }

    /** preparePosting 之后、库存余额锁之前；采购实现返回来源锁内当前累计数和冻结价格。 */
    default Map<Long, PurchasePostingBasis> purchasePostingBasis(SourcePosting posting) {
        return Map.of();
    }

    /** 正向回写源单（已入库、已退货、已领），在余额更新前重算源单状态 */
    void onPosted(SourcePosting posting);

    /** 反向回写，在余额更新前校验；源单那边不让退的抛业务错误 */
    void onUnposted(SourcePosting posting);
}
