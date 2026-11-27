package com.lingmao.scm.module.settle.dto;

/** 生成、刷新的结果：单号、版本号、拉进来几行 */
public record StatementCreatedVO(Long id, String no, Integer version, int added) {
}
