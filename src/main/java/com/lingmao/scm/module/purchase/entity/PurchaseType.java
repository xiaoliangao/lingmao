package com.lingmao.scm.module.purchase.entity;

import lombok.Getter;

/** 采购类型（PRD §6.6.2）：订单采购（多半由算料单生成）/ 备货采购（补货提醒生成，能填计划用于款号）/ 打样采购 */
@Getter
public enum PurchaseType {
    ORDER("订单采购"),
    STOCK("备货采购"),
    SAMPLE("打样采购");

    private final String label;

    PurchaseType(String label) {
        this.label = label;
    }
}
