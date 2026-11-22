package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 审核对话框里的评审信息（GET /api/board/orders/{id}/review，docs/api/02 §3.8，前端 types/order.ts 的 OrderReview）。
 * 只是参考，不拦审核
 *
 * @param shortages 缺口最大的 5 种料（按缺口 × 单价）；都够是空的
 * @param loads     每个默认加工厂在手多少；款式没设默认加工厂的不出
 */
public record OrderReview(List<Pack> packs, List<Shortage> shortages, List<Load> loads) {

    /** 每个款审核后会锁哪一版；problem 不是 null 的不能审（和提交、审核时的检查一样） */
    public record Pack(long styleId, String styleCode, String styleName, Long techPackId, Integer version, String problem) {
    }

    /** 缺的一种料，数量是库存单位（unit 是字典 unit 的值） */
    public record Shortage(long materialSkuId, String skuCode, String materialName, String skuColorName, String skuSpec, String unit,
                           BigDecimal need, BigDecimal available, BigDecimal gap, Integer leadDays, AttachmentVO image) {
    }

    /** 一个加工厂：这张订单里哪些款默认给它，它手上在制多少件（已下单、生产中的件数 − 已回货）、几张单 */
    public record Load(long factoryId, String factoryName, List<String> styleCodes, long wipQty, long orderCount) {
    }
}
