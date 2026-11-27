package com.lingmao.scm.module.settle.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 从草稿里移出几行：移出的单据回到"待对账"
 *
 * @param version 打开时拿到的版本号
 * @param itemIds 明细 id
 */
public record StatementItemsReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                                @NotEmpty(message = "选要移出的行") @Size(max = 500, message = "一次最多 500 行") List<Long> itemIds) {
}
