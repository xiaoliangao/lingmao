package com.lingmao.scm.module.inventory.entity;

import lombok.Getter;

/**
 * 库存日志的变动类型（inv_stock_log.change_type，前端 STOCK_CHANGE_TYPE_LABEL）：入库单、出库单的类型（名字和 InvDocType 一样），
 * 加上盘点单的盘盈、盘亏、期初录入
 */
@Getter
public enum StockChangeType {
    PURCHASE_IN("采购入库"),
    RETURN_IN("退料入库"),
    OTHER_IN("其他入库"),
    ISSUE_OUT("领料出库"),
    PURCHASE_RETURN("采购退货"),
    TRANSFER("调拨"),
    OTHER_OUT("其他出库"),
    CONSUME_OUT("耗料核销"),
    COUNT_GAIN("盘盈"),
    COUNT_LOSS("盘亏"),
    OPENING("期初");

    private final String label;

    StockChangeType(String label) {
        this.label = label;
    }

    public static StockChangeType of(InvDocType type) {
        return valueOf(type.name());
    }
}
