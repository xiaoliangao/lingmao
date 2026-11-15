package com.lingmao.scm.module.basic.entity;

import lombok.Getter;

/** 仓库类型（bas_warehouse.type 存名字）。以后质量模块加待检仓、不良品仓（架构 §14.2） */
@Getter
public enum WarehouseType {
    MATERIAL("物料仓"),
    PRODUCT("成品仓"),
    /** 工厂仓：料在工厂手上，领料就是移库。由供应商同步，页面上不能建 */
    FACTORY("工厂仓");

    private final String label;

    WarehouseType(String label) {
        this.label = label;
    }
}
