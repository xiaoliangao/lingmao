package com.lingmao.scm.module.production.dto;

/** 报进度的结果：warning 是不拦的提示（"前面还有节点没报完成"），status 是报完以后生产单的状态 */
public record NodeReportResult(String warning, String status) {
}
