package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DictDataSaveReq {

    @NotBlank(message = "字典类型不能为空")
    @Size(max = 64, message = "字典类型最长 64")
    private String typeCode;

    @NotBlank(message = "显示文本不能为空")
    @Size(max = 64, message = "显示文本最长 64")
    private String label;

    @NotBlank(message = "存储值不能为空")
    @Size(max = 64, message = "存储值最长 64")
    private String value;

    /** 越小越靠前 */
    private Integer sort = 0;

    /** 1 启用 0 停用 */
    private Integer status = 1;

    @Size(max = 255, message = "备注最长 255")
    private String remark;
}