package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 呆滞料的一行：一个物料仓 × 一个物料 SKU */
@Data
@EqualsAndHashCode(callSuper = true)
public class StaleMaterialRow extends MaterialSkuBrief {
    private Long warehouseId;
    private String warehouseName;
    /** 库存，库存单位 */
    private BigDecimal qty;
    /** 最后一次出入库（盘点不算）；没有记录是 null */
    private LocalDate lastDate;
    /** 多少天没动；没有记录是 null */
    private Integer idleDays;
    /** 最近一次入出库记的单价（元 / 库存单位）；没有是 null */
    private BigDecimal unitPrice;
    /** 库存 × 单价，估的；没有单价是 null */
    private BigDecimal amount;
}
