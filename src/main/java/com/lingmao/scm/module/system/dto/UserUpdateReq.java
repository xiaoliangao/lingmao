package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改用户的入参：登录名不能改，密码走单独的重置接口 */
@Data
public class UserUpdateReq {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64")
    private String nickname;

    @Size(max = 20, message = "手机号最长 20")
    private String phone;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
