package com.lingmao.scm.module.production.dto;

import java.util.List;

/**
 * 领料 · 料账页签（GET /api/production-orders/{id}/materials）：rows 是 [] 时不用领料。
 * goodQty 良品件数；ledgerStatus 料账状态（不进料账是 null）；settleDoc 没作废的耗料核销单，没有是 null（RFC 015 §4.1）
 */
public record ProductionMaterialsVO(List<MaterialPlanRowVO> rows, List<ProductionStockDocVO> docs, int goodQty,
                                    LedgerStatus ledgerStatus, SettleDocVO settleDoc) {
}
