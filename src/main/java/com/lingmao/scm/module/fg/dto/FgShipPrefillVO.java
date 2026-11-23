package com.lingmao.scm.module.fg.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 发货带出（前端 FgShipPrefill）：每个 SKU 一行 */
@Data
public class FgShipPrefillVO {
    private Long orderId;
    private String orderNo;
    private Long companyId;
    private String deliveryReq;
    private List<FgStyleOptionVO> styles;
    private List<Line> lines;

    /**
     * @param shippedQty 本单以外已审核的发货出库
     * @param maxQty     ⌊订单数 × 1.03⌋ − 已发，不小于 0
     * @param stockQty   出仓现库存；没给仓是 0
     */
    public record Line(Long orderItemId, Long styleSkuId, int orderQty, long shippedQty, long maxQty, int stockQty, BigDecimal price) {
    }
}
