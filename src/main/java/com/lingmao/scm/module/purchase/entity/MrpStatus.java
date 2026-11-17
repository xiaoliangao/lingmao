package com.lingmao.scm.module.purchase.entity;

/**
 * 算料单自己的状态（docs/api/03 §2.1，不是 DocStatus）：草稿 → 已确认 → 已关闭。
 * 已确认、没关闭的算料单是软占用（架构 §5.4）
 */
public enum MrpStatus {
    DRAFT,
    CONFIRMED,
    CLOSED
}
