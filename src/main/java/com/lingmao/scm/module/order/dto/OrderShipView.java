package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.common.doc.DocStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 给成品发货出库用的订单（RFC 008 §6.2）：能不能发看 status，每个 SKU 最多发多少按 lines 的件数算，单价按订单行
 *
 * @param deliveryReq 送货要求，"发到"的默认值
 * @param lines       全部行（变更单减成 0 的也在），按行号
 */
public record OrderShipView(long id, String no, DocStatus status, long companyId, String deliveryReq, List<Line> lines) {

    public record Line(long orderItemId, long styleId, long styleSkuId, int qty, BigDecimal price) {
    }
}
