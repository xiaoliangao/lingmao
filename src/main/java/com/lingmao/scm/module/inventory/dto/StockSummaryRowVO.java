package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 按物料汇总的一行（前端 StockSummaryRow）：物料仓合计、工厂仓合计、合计 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockSummaryRowVO extends MaterialSkuBrief {
    private BigDecimal materialQty;
    private BigDecimal factoryQty;
    private BigDecimal totalQty;
    private BigDecimal safetyStock;
}
