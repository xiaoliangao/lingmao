package com.lingmao.scm.module.price.entity;

import lombok.Getter;

/**
 * 价格的两种（PRD §6.10）。库里存英文名（prc_price.type），检查约束 ck_prc_price_type 按它认各自的必填列。
 * 放 entity 包：采购、生产、资料包取价、记价时要用它
 */
@Getter
public enum PriceType {
    /** 物料价：供应商 × 物料（SKU 选填，空 = 这个物料所有颜色同价），单位是物料的采购单位 */
    MATERIAL("物料价"),
    /** 加工价：工厂 × 款式 × 加工方式，单位是件。包工包料（FULL）的就是成品采购单价 */
    PROCESS("加工价");

    private final String label;

    PriceType(String label) {
        this.label = label;
    }
}
