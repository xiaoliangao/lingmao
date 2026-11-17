package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.inventory.entity.InvDocType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交给源单那边（StockSourceHandler 的 check、onPosted、onUnposted）的一张入库单 / 出库单：源单按它查上限、回写累计数
 *
 * @param docId 草稿还没建好（建草稿前查上限）时是 null
 */
public record SourcePosting(Long docId, String docNo, InvDocType docType, long sourceId, List<Line> lines) {

    /**
     * 一行
     *
     * @param seq          单上第几行（1004 的清单里写）
     * @param qty          库存单位
     * @param purchaseQty  采购单位数量，采购的两种才有（按它回写采购单）
     */
    public record Line(int seq, Long sourceItemId, long materialSkuId, BigDecimal qty, BigDecimal purchaseQty) {
    }
}
