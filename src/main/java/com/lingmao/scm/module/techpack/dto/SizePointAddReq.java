package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 加一个部位（POST /api/craft-standards/points）：进字典 size_point，value 就用名称 */
@Data
public class SizePointAddReq {
    @NotBlank(message = "部位名称不能为空")
    @Size(max = 50, message = "部位名称最长 50 个字")
    private String label;
    @Size(max = 500, message = "测量方法最长 500 个字")
    private String method;
}
