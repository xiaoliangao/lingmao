package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建、修改仓库（RFC 003 §6）。页面上只能建物料仓、成品仓，编码手填；编码、类型建好不改。
 * 工厂仓由供应商同步：修改时编码、名称、类型要和原来一样，只有地址、负责人、电话、备注能改
 */
@Data
public class WarehouseSaveReq {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^\\s*[A-Za-z0-9_-]{1,32}\\s*$", message = "仓库编码是 1-32 位字母、数字、横线或下划线")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称最长 100 个字")
    private String name;

    /** WarehouseType：MATERIAL 物料仓 / PRODUCT 成品仓 / FACTORY 工厂仓（工厂仓只能改，不能建） */
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "^(MATERIAL|PRODUCT|FACTORY)$", message = "仓库类型不对")
    private String type;

    @Size(max = 255, message = "地址最长 255 个字")
    private String address;

    /** 负责人 sys_user.id */
    private Long keeperId;

    @Size(max = 32, message = "电话最长 32 个字")
    private String phone;

    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
}
