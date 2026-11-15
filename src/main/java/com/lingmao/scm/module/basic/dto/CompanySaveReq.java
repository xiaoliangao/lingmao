package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建、修改主体公司。编码建好不改：修改时传的编码要和原来一样（大小写不算） */
@Data
public class CompanySaveReq {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^\\s*[A-Za-z0-9]{2,6}\\s*$", message = "编码是 2-6 位字母或数字")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称最长 100 个字")
    private String name;

    @NotBlank(message = "简称不能为空")
    @Size(max = 50, message = "简称最长 50 个字")
    private String shortName;

    @Pattern(regexp = "^$|^[0-9A-Za-z]{18}$", message = "统一社会信用代码是 18 位字母或数字")
    private String creditCode;

    @Size(max = 255, message = "地址最长 255 个字")
    private String address;

    @Size(max = 32, message = "电话最长 32 个字")
    private String phone;
}
