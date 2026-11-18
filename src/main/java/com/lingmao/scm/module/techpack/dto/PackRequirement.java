package com.lingmao.scm.module.techpack.dto;

/** 实际业务使用的一个版次/SKU，避免校验无关色码组合。 */
public record PackRequirement(Long packId, Long styleSkuId) {
}
