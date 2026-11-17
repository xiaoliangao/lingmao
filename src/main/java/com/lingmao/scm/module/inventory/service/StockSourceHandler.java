package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDocType;

import java.util.List;

/**
 * 入库单、出库单的源单那一边（架构 §5.2 "带出、回写源单都用接口反转依赖"）：inventory 不认识采购单、生产单，
 * 由 purchase（采购入库、采购退货）、production（领料出库、退料入库）实现，注册成 Bean。一种源单一个实现。
 * <p>
 * 都在 inventory 的事务里调：建草稿、改草稿时调 {@link #check}，审核时 check → 过账 → {@link #onPosted}，反审核时过账 → {@link #onUnposted}。
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
     */
    SourceDraft prefill(InvDocType docType, long sourceId, List<Long> itemIds, Long fromWarehouseId);

    /** 按源单最新的数查上限：超了抛 1004（data 是 InvOverRow[]），源单状态不让入、不让领抛 1000 */
    void check(SourcePosting posting);

    /** 审核过账以后回写源单（已入库、已退货、已领），重算源单状态 */
    void onPosted(SourcePosting posting);

    /** 反审核以后退回去；源单那边不让退的抛 1000 */
    void onUnposted(SourcePosting posting);
}
