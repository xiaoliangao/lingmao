package com.lingmao.scm.module.supplier.entity;

import lombok.Getter;

/**
 * 供应商的状态（架构 §4.6、PRD §6.8.2）。库里存英文名（sup_supplier.status）。
 * 怎么转写在 SupplierServiceImpl 的转换表 FLOW 里；改状态一律比较并交换（StatusCas）。
 * 放 entity 包：别的域（价格档案、采购、生产）要按它判断显示，只能用 supplier 的 service、dto、entity
 */
@Getter
public enum SupplierStatus {
    PENDING_ADMIT("待准入"),
    ADMITTING("准入审批中"),
    ACTIVE("合作中"),
    DISABLED("停用"),
    BLACKLISTED("黑名单");

    private final String label;

    SupplierStatus(String label) {
        this.label = label;
    }
}
