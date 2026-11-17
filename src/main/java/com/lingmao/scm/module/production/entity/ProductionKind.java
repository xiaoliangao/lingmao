package com.lingmao.scm.module.production.entity;

import lombok.Getter;

/**
 * 生产单的种类（架构 §4.10，docs/api/05）：排单时按加工方式定，之后不能改。
 * 外发加工单是清加工、半包（我方出料，单价是加工费），成品采购单是包工包料（工厂买料，单价是成品单价）
 */
@Getter
public enum ProductionKind {
    OUTSOURCE("外发加工单", "加工费"),
    BUY("成品采购单", "成品单价");

    /** 字典 process_mode 的内置项：包工包料。排出来是成品采购单，别的是外发加工单 */
    public static final String MODE_FULL = "FULL";

    private final String label;
    private final String priceLabel;

    ProductionKind(String label, String priceLabel) {
        this.label = label;
        this.priceLabel = priceLabel;
    }

    public static ProductionKind of(String processMode) {
        return MODE_FULL.equals(processMode) ? BUY : OUTSOURCE;
    }
}
