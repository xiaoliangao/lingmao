package com.lingmao.scm.module.style.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 改一个 SKU（PUT 整体覆盖）：条码有值时全表唯一；吊牌价空 = 用款式的 */
@Data
public class StyleSkuSaveReq {

    @Size(max = 32, message = "条码最长 32 位")
    @Pattern(regexp = "^\\S*$", message = "条码里不能有空格")
    private String barcode;

    @DecimalMin(value = "0", message = "吊牌价不能是负数")
    @Digits(integer = 12, fraction = 2, message = "吊牌价最多 2 位小数")
    private BigDecimal tagPrice;
}
