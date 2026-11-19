package com.lingmao.scm.module.fg.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/** 库存明细一行（前端 FgStockRow）：SKU 的显示字段由 Service 补 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockRowVO extends FgSkuFields {
    private Long id;
    private Long warehouseId;
    private String warehouseName;
    private Integer qty;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
