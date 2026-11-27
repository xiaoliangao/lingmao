package com.lingmao.scm.module.production.dto;

import java.time.LocalDate;

/**
 * 排单时看一家厂做这个款还能排多少（GET /api/factory-capacities/preview）：这个款归哪几条产能管，每条一行。
 * free 是这次最多能排的件数（≤ 0 = 排满了）；排了以后，交期在 until 及之前的已排会到 capacity
 *
 * @param categoryName 品类（带上级），不分品类的是 null
 * @param dailyQty     日产能（交期这个月用的）
 * @param until        卡住的那天：这一天之前要做完的最紧
 * @param capacity     从今天到 until 一共能做多少
 * @param load         交期在 until 及之前、还没做完的件数（不含这次）
 * @param free         这次最多能排多少
 */
public record CapacityPreviewVO(String categoryName, int dailyQty, LocalDate until, long capacity, long load, long free) {
}
