package com.lingmao.scm.module.production.dto;

/** 生产单的耗料核销单（没作废的那张）：id、单号、状态 DRAFT / APPROVED */
public record SettleDocVO(Long id, String no, String status) {
}
