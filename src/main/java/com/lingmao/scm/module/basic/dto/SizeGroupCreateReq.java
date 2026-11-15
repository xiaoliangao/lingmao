package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建尺码组：名称、全部尺码（空格、逗号、顿号隔开，按顺序）、基码（不填取中间那个） */
@Data
public class SizeGroupCreateReq {

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最长 50 个字")
    private String name;

    /** 如 "S M L XL"、"110,120,130" */
    @NotBlank(message = "尺码不能为空")
    @Size(max = 1000, message = "尺码太多了")
    private String sizes;

    @Size(max = 16, message = "基码最长 16 个字")
    private String baseSize;
}
