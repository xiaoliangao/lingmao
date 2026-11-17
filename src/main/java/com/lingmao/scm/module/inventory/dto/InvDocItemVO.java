package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 明细的一行（前端 InvDocItem） */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocItemVO extends MaterialSkuBrief {
    private Long id;
    private Integer seq;
    private BigDecimal qty;
    private String purchaseUnit;
    private BigDecimal purchaseQty;
    private BigDecimal conversionFactor;
    private BigDecimal price;
    private BigDecimal amount;
    private String dyeLot;
    private Integer rolls;
    private Long sourceItemId;
    private String overReason;
    private String remark;
    /** 出仓现在的库存：草稿、有出仓时才带，别的是 null */
    private BigDecimal fromStockQty;
    /** 这一行过过账（被库存日志指着）：不能删、不能换物料。前端编辑时用来灰掉删除 */
    private boolean posted;
}
