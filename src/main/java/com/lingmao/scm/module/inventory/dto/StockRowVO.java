package com.lingmao.scm.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 库存明细的一行（前端 StockRow）：仓库 × 物料 SKU */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockRowVO extends MaterialSkuBrief {
    /** inv_stock.id */
    private Long id;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String warehouseType;
    private BigDecimal qty;
    private BigDecimal safetyStock;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
