package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 重置密码的入参 */
@Data
public class UserPasswordReq {

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32")
    private String password;
}
