package com.lingmao.scm.module.inventory.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 从物料库加物料（POST /api/inventory-counts/{id}/items） */
public record CountAddItemsReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                               @NotEmpty(message = "请选物料") @Size(max = 2000, message = "一次最多加 2000 种") List<Long> skuIds) {
}
