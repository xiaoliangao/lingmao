package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/** 算料结果的一行（前端 MrpItem）。数字是算料时存下的；已转采购、已入库、齐料还差是现取的 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MrpItemVO extends MaterialSkuBrief {
    private Long id;
    private Integer seq;
    private String block;
    private Long supplierId;
    private String supplierName;
    private String supplierStatus;
    private BigDecimal grossQty;
    private BigDecimal reservePct;
    private BigDecimal needQty;
    private BigDecimal availStockQty;
    private BigDecimal useStockQty;
    private BigDecimal availTransitQty;
    private BigDecimal useTransitQty;
    private BigDecimal lateTransitQty;
    private BigDecimal netQty;
    private String purchaseUnit;
    private BigDecimal conversionFactor;
    private BigDecimal suggestQty;
    private BigDecimal refPrice;
    private BigDecimal estAmount;
    private BigDecimal convertedQty;
    private BigDecimal receivedQty;
    private BigDecimal shortQty;
    private List<String> orderNos;
    private List<String> styleCodes;
    private List<MrpTransitUseVO> transitUses;
    private List<MrpLateTransitVO> lateTransits;
    private boolean canConvert;
}
