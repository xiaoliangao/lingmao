package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

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

    /** 越小越靠前。不传用默认值；保存是整体覆盖，显式传 null 等于清空，这一列不能空，所以拦住（下同） */
    @NotNull(message = "排序不能为空")
    private Integer sort = 0;

    /** 1 启用 0 停用 */
    @NotNull(message = "状态不能为空")
    private Integer status = 1;

    @Size(max = 255, message = "备注最长 255")
    private String remark;

    /** 数值，类型有 num_label 时才用（备料比 %、默认天数） */
    @Digits(integer = 10, fraction = 4, message = "数值最多 10 位整数、4 位小数")
    private BigDecimal numValue;
}