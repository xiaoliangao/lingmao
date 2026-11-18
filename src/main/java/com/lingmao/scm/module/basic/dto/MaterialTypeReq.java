package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 新建、编辑物料时当场加一个物料类型（PRD §6.1.5 规则 6，Q40） */
@Data
public class MaterialTypeReq {

    @NotBlank(message = "请填物料类型的名称")
    @Size(max = 64, message = "名称最长 64")
    private String label;

    /** 备料比 %，物料上没设备料比时用它；由生产评估后填，不填 = 0（Q18） */
    @DecimalMin(value = "0", message = "备料比不能是负数")
    @DecimalMax(value = "100", message = "备料比最多 100%")
    @Digits(integer = 3, fraction = 2, message = "备料比最多 2 位小数")
    private BigDecimal reservePct;
}
