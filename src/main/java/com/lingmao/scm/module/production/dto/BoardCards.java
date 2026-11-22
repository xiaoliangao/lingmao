package com.lingmao.scm.module.production.dto;

/**
 * 看板数字卡（GET /api/production-board/cards）：在制 = 生产中的单数、件数（件数 − 已回良品，良品 = 回货 − 次品）；待开工 = 已下单的单数、件数（同上）；
 * 临期、逾期、节点延期、有异常照货期预警；本月回货 = 本月登记、没作废的回货单的良品件数；nearDays 是临期天数
 */
public record BoardCards(long runningCount, long runningQty, long waitingCount, long waitingQty, long nearCount, long lateCount,
                         long nodeLateCount, long issueCount, long monthReturnedQty, int nearDays) {
}
