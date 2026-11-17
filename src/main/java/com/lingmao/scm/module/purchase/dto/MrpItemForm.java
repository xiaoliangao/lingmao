package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 改算料单的一行（PUT /api/mrp-calcs/{id}/items/{itemId}，前端 MrpItemForm），一次改一格：
 * 改了本次使用库存或在途 → 重分在途、重算净需求和建议采购（suggestQty 不看）；只改了建议采购 → 照填的存；换了供应商 → 重取参考单价。
 * 已确认的单只能换供应商，另外三格发原值
 */
@Data
public class MrpItemForm {
    @NotNull(message = "缺少版本号，请刷新")
    private Integer version;
    private Long supplierId;
    @NotNull(message = "请填本次使用库存")
    @DecimalMin(value = "0", message = "本次使用库存不能是负数")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal useStockQty;
    @NotNull(message = "请填本次使用在途")
    @DecimalMin(value = "0", message = "本次使用在途不能是负数")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal useTransitQty;
    @NotNull(message = "请填建议采购量")
    @DecimalMin(value = "0", message = "建议采购量不能是负数")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal suggestQty;
}
