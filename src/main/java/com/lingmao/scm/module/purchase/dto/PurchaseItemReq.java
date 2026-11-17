package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新建、改草稿的一行（前端 PurchaseItemForm）。整体覆盖：没填的发 null。数量要 > 0（Service 按行号报）；
 * price 是 null 用默认单价；和档案现价不一样时 priceChangeReason 必填
 */
@Data
public class PurchaseItemReq {
    /** 从算料单生成的行原样带回；手加的行是 null */
    private Long calcItemId;
    @NotNull(message = "明细要选物料")
    private Long materialSkuId;
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal qty;
    @Digits(integer = 10, fraction = 4, message = "单价最多 4 位小数")
    private BigDecimal price;
    @Size(max = 255, message = "改价原因最多 255 个字")
    private String priceChangeReason;
    private LocalDate lineDueDate;
    private LocalDate expectDate;
}
