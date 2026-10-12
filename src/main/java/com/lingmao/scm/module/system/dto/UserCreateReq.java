package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增用户的入参：比修改多了登录名和初始密码 */
@Data
public class UserCreateReq {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-z0-9_]{3,32}$", message = "用户名只能是 3-32 位小写字母、数字、下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64")
    private String nickname;

    @Size(max = 20, message = "手机号最长 20")
    private String phone;
}
