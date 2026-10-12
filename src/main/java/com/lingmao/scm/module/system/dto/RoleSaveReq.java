package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增 / 修改角色的入参 */
@Data
public class RoleSaveReq {

    @NotBlank(message = "编码不能为空")
    @Size(max = 64, message = "编码最长 64")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "编码只能是小写字母、数字、下划线，且以字母开头")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64")
    private String name;

    private Integer sort = 0;

    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status = 1;

    @Size(max = 255, message = "备注最长 255")
    private String remark;
}
