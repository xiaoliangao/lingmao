package com.lingmao.scm.module.settle.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 写差异说明（和对方谈的结果）：只改传了的几处
 *
 * @param version 打开时拿到的版本号
 * @param notes   哪一处（比对结果的 key）写什么；写空串是清掉
 */
public record CompareNotesReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                              @NotNull(message = "缺少要写说明的差异") @Size(max = 2000, message = "一次最多写 2000 处说明") List<@Valid Note> notes) {

    public record Note(@NotNull(message = "缺少是哪一处差异（key）") String key, @Size(max = 255, message = "说明最多 255 个字") String note) {
    }
}
