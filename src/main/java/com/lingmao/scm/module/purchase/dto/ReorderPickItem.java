package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 补货清单勾的一行（前端 ReorderPickItem）：数量是采购单位 */
@Data
public class ReorderPickItem {
    @NotNull(message = "请选物料")
    private Long materialSkuId;
    @NotNull(message = "有的行还没选供应商")
    private Long supplierId;
    @NotNull(message = "请填补货量")
    @DecimalMin(value = "0", inclusive = false, message = "补货量要大于 0")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal qty;
}
