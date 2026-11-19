package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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

    /** 默认主体（bas_company.id），下拉调 /api/companies/options；空 = 单据上取唯一启用的主体 */
    private Long defaultCompanyId;

    @Size(max = 64, message = "部门最长 64")
    private String dept;

    @Size(max = 64, message = "岗位最长 64")
    private String post;

    /** 设计师编号：两位数字 01-99，进款号；不是设计师的不填 */
    @Pattern(regexp = "^$|^(0[1-9]|[1-9][0-9])$", message = "设计师编号是 01-99 的两位数字")
    private String designerNo;

    /** 能看的品类线（字典 product_line 的 value），至少一条（RFC 013 §2）；超级管理员不看它 */
    @NotEmpty(message = "品类线至少选一条")
    @Size(max = 20, message = "品类线最多 20 条")
    private List<String> productLines;
}
