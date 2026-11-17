package com.lingmao.scm.module.order.entity;

import lombok.Getter;

/** 订单来源（ord_order.source） */
@Getter
public enum OrderSource {
    MANUAL("手工"),
    IMPORT("Excel 导入"),
    REPEAT("翻单");

    private final String label;

    OrderSource(String label) {
        this.label = label;
    }
}
