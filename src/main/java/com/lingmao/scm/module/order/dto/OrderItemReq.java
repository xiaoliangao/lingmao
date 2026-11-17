package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 明细的一行（前端 OrderItemForm）：页面一个款一个单价、行交期、备注，保存时展开到每个 SKU；件数 0 的格子不发 */
@Data
public class OrderItemReq {

    @NotNull(message = "明细缺少 SKU")
    private Long styleSkuId;

    @NotNull(message = "请填件数")
    @Positive(message = "件数要大于 0")
    private Integer qty;

    @PositiveOrZero(message = "单价不能是负数")
    private BigDecimal price;

    private LocalDate lineDueDate;

    @Size(max = 255, message = "行备注最多 255 个字")
    private String remark;
}
