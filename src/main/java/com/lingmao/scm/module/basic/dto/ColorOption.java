package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Color;

/** 颜色下拉、选色的一项（GET /api/colors/options，只有启用的） */
public record ColorOption(Long id, String code, String name, String hex) {

    public static ColorOption from(Color color) {
        return new ColorOption(color.getId(), color.getCode(), color.getName(), color.getHex());
    }
}
