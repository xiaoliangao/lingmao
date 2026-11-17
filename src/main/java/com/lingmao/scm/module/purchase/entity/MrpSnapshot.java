package com.lingmao.scm.module.purchase.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 算料时的输入（mrp_calc.input_snapshot）：订单、件数、生产 BOM 存着备查；赶不上的在途详情页要列（docs/api/03 §3.2.2"计算时的快照"），
 * 它不进结果行的数，单独存这里
 */
public record MrpSnapshot(List<Order> orders, List<Late> lates) {

    /** 一张订单：承诺交期、要料日期，每个 SKU 的件数和用的生产 BOM */
    public record Order(long orderId, String orderNo, LocalDate commitDate, LocalDate needDate, List<Line> lines) {
    }

    public record Line(long styleSkuId, int qty, Long techPackId) {
    }

    /** 一条赶不上的在途：哪个物料 SKU、哪条采购行、当时还能用的数（库存单位）、预计到货 */
    public record Late(long skuId, long purchaseItemId, BigDecimal freeQty, LocalDate eta) {
    }
}
