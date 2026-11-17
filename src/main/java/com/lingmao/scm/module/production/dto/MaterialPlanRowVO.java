package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 领料页签一行（前端 MaterialPlanRow）：只算资料包里物料来源 = 我方采购的行，数量是库存单位 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialPlanRowVO extends MaterialSkuBrief {
    /** 应领 = Σ 件数 × BOM 用量 ×（1 + 损耗率） */
    private BigDecimal needQty;
    /** 已领 = 领料出库 − 退料入库（只算已审核的） */
    private BigDecimal issuedQty;
    /** 还差 = 应领 − 已领，不小于 0 */
    private BigDecimal remainQty;
    /** 物料仓（启用的）现在的库存合计 */
    private BigDecimal stockQty;
}
