package com.lingmao.scm.module.purchase.entity;

/** 算料结果块：自购（要算、要买）/ 厂购（只列不算）。TRADE 开时再加 CUSTOMER 待客户提供 */
public enum MrpBlock {
    PURCHASE,
    FACTORY
}
