package com.lingmao.scm.module.dev.entity;

/** 评审人属于不可变评审，seq 保存提交顺序。 */
public record SampleReviewer(String tenantKey, Long companyId, Long reviewId, Long userId, Integer seq) { }
