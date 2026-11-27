package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 工厂料账 · 按工厂物料的一行（前端 LedgerMaterialRow）：一个工厂仓 × 一个物料 SKU，数量是库存单位 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerMaterialRowVO extends MaterialSkuBrief {
    private Long factoryId;
    private String factoryShortName;
    /** 这个工厂的工厂仓（点一行去库存日志用）；还没建工厂仓的是 null */
    private Long warehouseId;
    /** 账面：工厂仓现在的库存 */
    private BigDecimal bookQty;
    /** 未核销的耗料：这个工厂还没核销的单（在制、待核销、核销中）按目前的良品算出来的应耗合计 */
    private BigDecimal pendingQty;
    /** 估计手上 = 账面 − 未核销的耗料；小于 0 说明工厂用的比账上多 */
    private BigDecimal estimateQty;
    /** 涉及几张没核销的单 */
    private int orderCount;
}
