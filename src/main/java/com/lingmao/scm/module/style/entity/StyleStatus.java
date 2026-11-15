package com.lingmao.scm.module.style.entity;

import lombok.Getter;

/**
 * 款式的状态（RFC 005 §4）。库里存英文名（sty_style.status）。怎么转写在 StyleServiceImpl 的转换表 FLOW 里，
 * 改状态一律比较并交换（StatusCas）。放 entity 包：下游（价格档案、需求订单）要按它判断显示
 */
@Getter
public enum StyleStatus {
    DRAFT("草稿"),
    ENABLED("启用"),
    DISABLED("停用");

    private final String label;

    StyleStatus(String label) {
        this.label = label;
    }
}
