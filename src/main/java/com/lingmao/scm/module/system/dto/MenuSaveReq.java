package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增 / 修改菜单的入参 */
@Data
public class MenuSaveReq {

    /** 上级菜单 id，0 表示顶层 */
    @NotNull(message = "上级菜单不能为空")
    private Long parentId;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64")
    private String name;

    /** 1 目录 2 菜单 3 按钮 */
    @NotNull(message = "类型不能为空")
    @Min(value = 1, message = "类型只能是 1、2、3")
    @Max(value = 3, message = "类型只能是 1、2、3")
    private Integer type;

    @Size(max = 128, message = "路由地址最长 128")
    private String path;

    @Size(max = 128, message = "组件路径最长 128")
    private String component;

    @Size(max = 100, message = "权限标识最长 100")
    private String perms;

    @Size(max = 64, message = "图标最长 64")
    private String icon;

    private Integer sort = 0;

    private Boolean visible = true;

    private Integer status = 1;
}
