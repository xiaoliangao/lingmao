package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.validation.MaxBytes;
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
    @Size(min = 8, max = 32, message = "密码长度 8-32 位")
    @MaxBytes(value = 72, message = "密码太长：最多 72 个字节，一个汉字算 3 个")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64")
    private String nickname;

    @Size(max = 20, message = "手机号最长 20")
    private String phone;

    /** 默认主体（bas_company.id），下拉调 /api/companies/options；空 = 单据上取唯一启用的主体 */
    private Long defaultCompanyId;

    @Size(max = 64, message = "部门最长 64")
    private String dept;

    @Size(max = 64, message = "岗位最长 64")
    private String post;

    /** 设计师编号：两位数字 01-99，进款号；不是设计师的不填 */
    @Pattern(regexp = "^$|^(0[1-9]|[1-9][0-9])$", message = "设计师编号是 01-99 的两位数字")
    private String designerNo;
}
