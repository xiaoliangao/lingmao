package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/** 补货清单的一行（前端 ReorderRow）。数量是库存单位，只有 suggestQty、price 按采购单位 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReorderRowVO extends MaterialSkuBrief {
    private Long defaultSupplierId;
    private String defaultSupplierName;
    private String defaultSupplierStatus;
    private BigDecimal stockQty;
    private BigDecimal transitQty;
    private BigDecimal heldQty;
    private BigDecimal safetyStock;
    private BigDecimal gapQty;
    private String purchaseUnit;
    private BigDecimal conversionFactor;
    private BigDecimal suggestQty;
    private BigDecimal price;
    /** 当前用户可见的完整备货单数，openDrafts 只包含最新 20 张。 */
    private long openDraftsTotal;
    private List<OpenDraft> openDrafts;

    /** 已经开了、还没审核的备货采购单 */
    public record OpenDraft(long id, String no, String status) {
    }
}
