package com.lingmao.scm.module.purchase.entity;

/** 算料结果块：我方采购（要算、要买）/ 工厂自备（只列不算）。TRADE 开时再加 CUSTOMER 待客户提供 */
public enum MrpBlock {
    PURCHASE,
    FACTORY
}
