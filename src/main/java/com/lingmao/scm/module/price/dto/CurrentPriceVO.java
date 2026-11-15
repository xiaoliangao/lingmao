package com.lingmao.scm.module.price.dto;

import java.math.BigDecimal;

/**
 * 录价对话框用（GET /api/prices/current）：选好供应商、物料（SKU）或款式、加工方式、生效日期以后，
 * 显示"现价 18.50（11-01 起）→ 19.00"，决定调价原因要不要必填。
 * <ul>
 *   <li>current：那一天的现价，口径同 PriceService：物料价先找这个 SKU 的、没有再找物料层的。
 *       current.materialSkuId 和要录的 SKU 一样才算"同一层级"（RFC 006 §3），不一样是"第一次给这个 SKU 单独定价"</li>
 *   <li>unit：这条价的单位（物料现在的采购单位，或件）；还没选物料是空</li>
 *   <li>supplierTaxPct：供应商的税率，税率框的默认值；供应商没填是空（那就要手填）</li>
 * </ul>
 */
public record CurrentPriceVO(PriceRef current, String unit, BigDecimal supplierTaxPct) {
}
