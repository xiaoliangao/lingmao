package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 在尺码组末尾追加尺码：一个或几个，空格、逗号隔开，按顺序接在最后 */
@Data
public class SizeAppendReq {

    @NotBlank(message = "尺码不能为空")
    @Size(max = 500, message = "尺码太多了")
    private String sizes;
}
