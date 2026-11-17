package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/**
 * 工艺指示的段（tp_craft_section.section，PRD §6.4.4）。照甲方线下工艺单分三块：车间缝制、后道、注意事项。
 * 是枚举不是字典：PDF 的版式按它排，甲方加一段版式就要跟着改。顺序就是页面、PDF 上的顺序
 */
@Getter
public enum CraftSection {
    CUTTING("裁剪要求", "车间缝制"),
    SEWING("缝制要求", "车间缝制"),
    PART("部位工艺细则", "车间缝制"),
    QUALITY("做工品质", "车间缝制"),
    BUTTON("锁钉要求", "后道"),
    IRONING("整烫说明", "后道"),
    LABEL("洗唛 / 烫标", "后道"),
    PACKING("包装要求", "后道"),
    NOTE("注意事项", "注意事项");

    private final String label;
    private final String group;

    CraftSection(String label, String group) {
        this.label = label;
        this.group = group;
    }
}
