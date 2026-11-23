package com.lingmao.scm.module.dev.entity;

/** 本版真实尺码组成员，数据库按 sizeGroupId + sizeCode 校验来源。 */
public record SampleOrderSize(String tenantKey, Long companyId, Long sampleOrderId, Long sizeGroupId, Integer seq,
                              String sizeCode) { }
