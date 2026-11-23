package com.lingmao.scm.module.fg.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 明细一行（前端 FgDocItemForm）：一个 SKU 一行 */
@Data
public class FgDocItemReq {
    @NotNull(message = "明细要选 SKU")
    private Long styleSkuId;
    @NotNull(message = "明细要填件数")
    @Min(value = 1, message = "件数要大于 0")
    @Max(value = 99_999_999, message = "件数太大了")
    private Integer qty;
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
