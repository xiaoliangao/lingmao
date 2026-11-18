package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/** 由源单生成：带出来的单头默认值 + 明细（GET …/from-purchase/{id}、…/from-production/{id}，前端 InvDocDraft），不存库 */
@Data
public class InvDocDraftVO {
    /** Source-company projection available to warehouse users without ordinary source-detail access. */
    private Long companyId;
    private InvDocType docType;
    private DocRef source;
    private Long partnerId;
    private String partnerName;
    private Long fromWarehouseId;
    private String fromWarehouseName;
    private Long toWarehouseId;
    private String toWarehouseName;
    private List<Item> items;

    /** 前端 InvDocDraftItem */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Item extends MaterialSkuBrief {
        private Long sourceItemId;
        private String purchaseUnit;
        private BigDecimal conversionFactor;
        private BigDecimal price;
        private BigDecimal suggestQty;
        private BigDecimal maxQty;
        private BigDecimal orderedQty;
        private BigDecimal receivedQty;
        private BigDecimal returnedQty;
        private BigDecimal needQty;
        private BigDecimal issuedQty;
        /** 缸号：带不出来，都是 null（让人填） */
        private String dyeLot;
    }
}
