package com.lingmao.scm.module.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 新建、改草稿的明细一行（前端 InvDocItemForm）。保存是整体覆盖：没填的发 null */
@Data
public class InvDocItemReq {
    /** 已有的行原样带回，新加的行 null。过过账的行靠它认：不能删、不能换物料 */
    private Long id;
    /** 源单生成的草稿：原样带回（采购明细 id）；手建的是 null */
    private Long sourceItemId;
    @NotNull(message = "明细要选物料")
    private Long materialSkuId;
    /** 库存单位；采购入库、采购退货发 null，由 purchaseQty 换算 */
    @DecimalMin(value = "0", inclusive = false, message = "数量要大于 0")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal qty;
    @DecimalMin(value = "0", inclusive = false, message = "数量要大于 0")
    @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
    private BigDecimal purchaseQty;
    @DecimalMin(value = "0", message = "单价不能是负数")
    @Digits(integer = 10, fraction = 4, message = "单价最多 4 位小数")
    private BigDecimal price;
    @Size(max = 32, message = "缸号最多 32 个字")
    private String dyeLot;
    @Min(value = 0, message = "卷数不能是负数")
    private Integer rolls;
    @Size(max = 255, message = "超领原因最多 255 个字")
    private String overReason;
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
