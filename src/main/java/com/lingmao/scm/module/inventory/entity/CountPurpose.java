package com.lingmao.scm.module.inventory.entity;

import lombok.Getter;

/** 盘点用途：日常盘点 / 期初录入（账面数都是 0，范围只能手选，已经有库存的 SKU 不能进，PRD §6.6.8、§10） */
@Getter
public enum CountPurpose {
    DAILY("日常盘点"),
    OPENING("期初录入");

    private final String label;

    CountPurpose(String label) {
        this.label = label;
    }
}
