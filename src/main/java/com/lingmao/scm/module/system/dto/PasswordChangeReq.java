package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.validation.MaxBytes;
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
    @MaxBytes(value = 72, message = "密码太长：最多 72 个字节，一个汉字算 3 个")
    private String newPassword;
}
