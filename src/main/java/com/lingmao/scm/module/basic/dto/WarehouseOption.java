package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Warehouse;

/** 仓库下拉的一项（GET /api/warehouses/options，只有启用的，能按 type 筛） */
public record WarehouseOption(Long id, String code, String name, String type, Long factoryId) {

    public static WarehouseOption from(Warehouse w) {
        return new WarehouseOption(w.getId(), w.getCode(), w.getName(), w.getType(), w.getFactoryId());
    }
}
