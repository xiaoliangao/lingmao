package com.lingmao.scm.module.order.entity;

import lombok.Getter;

/**
 * 变更类型（ord_change.change_types、ord_change_item.change_type）：由改动算出来，不用选。
 * 数量是原有 SKU 改件数；配色配码是加一个 SKU（原 0）或减掉一个 SKU（新 0）；CANCEL 只在单头，没有明细
 */
@Getter
public enum ChangeType {
    QTY("数量"),
    DUE("交期"),
    COLOR_SIZE("配色配码"),
    PRICE("单价"),
    TECH_PACK("资料包版本"),
    CANCEL("取消订单");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }
}
