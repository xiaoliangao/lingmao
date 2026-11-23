package com.lingmao.scm.module.biz.entity;

/** 公共标签当前真实存在的对象；V59 在 V58 基础上接入实际样衣父头，与固定 CASE 一一对应，不接受任意表名。 */
public enum TagObjectType {
    STYLE, MATERIAL, SUPPLIER, TECH_PACK, ORDER, MRP_CALC, PURCHASE_ORDER,
    INV_DOC, INV_COUNT, PRODUCTION_ORDER, PRODUCTION_RECEIPT, FG_DOC, SAMPLE
}
