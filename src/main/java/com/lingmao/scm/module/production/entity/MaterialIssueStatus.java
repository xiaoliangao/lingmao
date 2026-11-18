package com.lingmao.scm.module.production.entity;

/** 领料状态（prd_order.issue_status）：资料包里没有自购的料时整列是 null（不用领料） */
public enum MaterialIssueStatus {
    NONE, PARTIAL, DONE
}
