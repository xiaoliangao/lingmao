package com.lingmao.scm.module.purchase.entity;

/** 算料单为什么关闭：订单的生产单都领完料（自动）/ 手动关闭 / 重新算料的新单确认了（自动） */
public enum MrpCloseReason {
    ISSUED,
    MANUAL,
    RECALC
}
