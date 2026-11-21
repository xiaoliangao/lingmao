package com.lingmao.scm.module.production.dto;

/**
 * 进度总览一行的风险（RFC 016 §1.2）：late 交期过了还没回齐、near 还剩 ≤ 7 天没回齐、unscheduled 没排完、
 * nodeLate 这个款的生产单有节点延期、issue 有没关闭的异常、production 生产单有逾期 / 临期 / 节点延期 / 异常
 */
public record OrderProgressRiskVO(boolean late, boolean near, boolean unscheduled, boolean nodeLate, boolean issue, boolean production) {
}
