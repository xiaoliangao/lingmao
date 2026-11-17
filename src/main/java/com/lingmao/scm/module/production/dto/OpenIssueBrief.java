package com.lingmao.scm.module.production.dto;

import java.time.LocalDate;

/** 详情顶上的红条：没关闭的异常 */
public record OpenIssueBrief(Long id, String issueType, String description, LocalDate newExpectDate) {
}
