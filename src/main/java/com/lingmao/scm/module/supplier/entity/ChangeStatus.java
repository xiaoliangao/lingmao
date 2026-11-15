package com.lingmao.scm.module.supplier.entity;

import lombok.Getter;

/** 关键字段待批改动的状态（sup_supplier_change.status）。一家同时只有一份 PENDING（uk_sup_change_pending） */
@Getter
public enum ChangeStatus {
    PENDING("待批"),
    APPROVED("已批"),
    REJECTED("驳回"),
    WITHDRAWN("撤回");

    private final String label;

    ChangeStatus(String label) {
        this.label = label;
    }
}
