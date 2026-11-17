package com.lingmao.scm.module.inventory.entity;

/** 一张表两种单据（架构 §4.10）：IN 物料入库单（RK）/ OUT 物料出库单（CK）。值和 BizType.INV_DOC 的 kind 一样 */
public enum InvDocKind {
    IN,
    OUT
}
