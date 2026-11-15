package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 重置密码的入参 */
@Data
public class UserPasswordReq {

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度 8-32 位")
    @MaxBytes(value = 72, message = "密码太长：最多 72 个字节，一个汉字算 3 个")
    private String password;
}
