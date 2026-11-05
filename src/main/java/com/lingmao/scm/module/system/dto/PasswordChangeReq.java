package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 自己改密码的入参：要先输对旧密码，防止别人趁电脑没锁改掉密码 */
@Data
public class PasswordChangeReq {

    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 32, message = "新密码长度 8-32 位")
    private String newPassword;
}
