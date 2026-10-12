package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改的入参。
 * 为什么不直接用实体接参数：实体有 id、createTime、deleted 这些字段，前端不该能传；
 * 校验规则也只属于"接口入参"，不属于"表"。DTO 和实体分开，接口形状和表结构才能各自演进。
 */
@Data
public class DictTypeSaveReq {

    @NotBlank(message = "编码不能为空")
    @Size(max = 64, message = "编码最长 64")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "编码只能是小写字母、数字、下划线，且以字母开头")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64")
    private String name;

    @Size(max = 255, message = "备注最长 255")
    private String remark;

    /** 1 启用 0 停用，默认启用 */
    private Integer status = 1;
}
