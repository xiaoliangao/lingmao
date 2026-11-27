package com.lingmao.scm.module.settle.entity;

/** 对账单的状态（RFC 019 §5.1）：草稿 → 已发送 → 已确认；已发送能撤回；只有草稿能作废。PAID 留给付款计划 */
public enum StatementStatus {
    DRAFT, SENT, CONFIRMED, PAID, VOIDED
}
