package com.lingmao.scm.module.dev.entity;

/** 样衣当前可选颜色；历史打样单保留自己的选择快照。 */
public record SampleColor(String tenantKey, Long companyId, Long sampleId, Long colorId, Integer seq) { }
