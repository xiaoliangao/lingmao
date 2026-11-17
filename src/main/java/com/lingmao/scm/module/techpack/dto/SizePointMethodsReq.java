package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 改几个部位的默认测量方法（PUT /api/craft-standards/points），只改传了的 */
@Data
public class SizePointMethodsReq {
    @NotNull(message = "points 不能为空")
    @Valid
    private List<Item> points;

    @Data
    public static class Item {
        @NotBlank(message = "部位的 value 不能为空")
        private String value;
        @Size(max = 500, message = "测量方法最长 500 个字")
        private String method;
    }
}
