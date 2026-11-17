package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 改一种物料类型的备料比 */
@Data
public class ReservePctReq {
    @NotBlank(message = "请选物料类型")
    private String materialType;
    @NotNull(message = "请填备料比")
    @DecimalMin(value = "0", message = "备料比不能小于 0")
    @DecimalMax(value = "50", message = "备料比不能大于 50%")
    @Digits(integer = 2, fraction = 2, message = "备料比最多 2 位小数")
    private BigDecimal pct;
}
