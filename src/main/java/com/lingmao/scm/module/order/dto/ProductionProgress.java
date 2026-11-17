package com.lingmao.scm.module.order.dto;

/**
 * 一张订单的生产单进度：production 算好交给订单，订单按它改自己的状态（docs/api/05 §3.9、PRD §6.7.5 规则 3）
 *
 * @param audited     有审核过的生产单（已下单、生产中、已完成、已结案）
 * @param allFinished 没作废的生产单都是已完成或已结案；一张都没有是 false
 */
public record ProductionProgress(boolean audited, boolean allFinished) {
}
