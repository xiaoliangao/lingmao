package com.lingmao.scm.module.production.dto;

import java.time.LocalDate;
import java.util.List;

/** 进度总览里一张生产单的小块：ProductionRow 的一部分，不带价格。returnedQty 含次品，良品 = returnedQty − defectQty */
public record ProductionBriefVO(Long id, String no, String kind, String status, String factoryShortName, int totalQty,
                                int returnedQty, int defectQty, LocalDate dueDate, LocalDate expectDate, Integer daysLeft,
                                String currentNodeName, List<NodeDot> nodes, RiskVO risk) {
}
