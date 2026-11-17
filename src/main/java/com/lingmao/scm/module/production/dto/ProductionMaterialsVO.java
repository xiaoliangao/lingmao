package com.lingmao.scm.module.production.dto;

import java.util.List;

/** 领料页签（GET /api/production-orders/{id}/materials）：rows 是 [] 时不用领料 */
public record ProductionMaterialsVO(List<MaterialPlanRowVO> rows, List<ProductionStockDocVO> docs) {
}
