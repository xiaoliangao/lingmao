package com.lingmao.scm.module.production.entity;

/** 异常自己的状态，不是单据状态：待处理 → 处理中 → 已关闭，待处理也能直接关闭 */
public enum IssueStatus {
    OPEN, HANDLING, CLOSED
}
