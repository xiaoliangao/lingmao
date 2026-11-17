package com.lingmao.scm.module.order.entity;

import lombok.Getter;

/** 变更的责任方（ord_change.responsible_party）。CUSTOMER 是贸易代工留的，TRADE 关时不收 */
@Getter
public enum ResponsibleParty {
    SELF("我方"),
    FACTORY("工厂"),
    SUPPLIER("供应商"),
    CUSTOMER("客户");

    private final String label;

    ResponsibleParty(String label) {
        this.label = label;
    }
}
