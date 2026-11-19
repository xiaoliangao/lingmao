package com.lingmao.scm.module.production.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 给成品入库用的回货单（RFC 008 §6.2）：带出、建单、改草稿、审核都先拿它（拿的时候锁住回货单）
 *
 * @param prdOrderKind 生产单的 kind：OUTSOURCE 外发加工单 / BUY 成品采购单
 * @param warehouseId  回货单上的收货仓，没填是 null
 * @param items        回货明细，按行号；qty 含次品
 */
public record ReceiptForStock(long id, String no, long companyId, long prdOrderId, String prdOrderNo, String prdOrderKind,
                              long styleId, long factoryId, LocalDate receiptDate, Long warehouseId, List<Item> items) {

    public record Item(long id, long styleSkuId, int qty, int defectQty) {

        /** 良品 */
        public int goodQty() {
            return qty - defectQty;
        }
    }
}
