package com.lingmao.scm.module.dev.entity;

/** 真实颜色 ID 和派单版本显示快照；不依赖会变化的样衣颜色关联。 */
public record SampleOrderColor(String tenantKey, Long companyId, Long sampleOrderId, Long colorId, Integer seq,
                               String colorCode, String colorName, String colorHex) { }
