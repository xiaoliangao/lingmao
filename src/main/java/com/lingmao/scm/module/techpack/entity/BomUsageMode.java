package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/** 用量方式（tp_bom_item.usage_mode）：统一 = 所有尺码一个用量（usage_qty）；分码 = 每个尺码一个（tp_bom_item_size） */
@Getter
public enum BomUsageMode {
    UNIFORM("统一"),
    BY_SIZE("分码");

    private final String label;

    BomUsageMode(String label) {
        this.label = label;
    }
}
