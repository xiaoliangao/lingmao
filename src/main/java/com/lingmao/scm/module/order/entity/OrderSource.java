package com.lingmao.scm.module.order.entity;

import lombok.Getter;

/** 订单来源（ord_order.source） */
@Getter
public enum OrderSource {
    MANUAL("手工"),
    IMPORT("Excel 导入"),
    /** 翻单、补单：从一张旧订单复制的（RFC 016 §2），订单类型另看 order_type */
    REPEAT("翻单 / 补单");

    private final String label;

    OrderSource(String label) {
        this.label = label;
    }
}
