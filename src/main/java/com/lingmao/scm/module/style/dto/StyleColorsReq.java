package com.lingmao.scm.module.style.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一组颜色 id：加颜色（追加在后面）、排顺序（款式上全部颜色的新顺序）都用它 */
@Data
public class StyleColorsReq {

    @NotEmpty(message = "请选择颜色")
    @Size(max = 100, message = "一次最多 100 个颜色")
    private List<Long> colorIds = new ArrayList<>();
}
