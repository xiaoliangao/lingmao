package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginReq {

    /** 限长：用户名会拼进 Redis 的失败计数键，不限长的话谁都能往 Redis 里写任意大的键（建用户时最长也是 32） */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名最长 32 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
