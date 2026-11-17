package com.lingmao.scm.module.purchase.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 订单的要料日期（架构 §5.4）：已排单的取生产单"裁剪"节点的计划开始。这是 production 的数，production 依赖 purchase，
 * purchase 不能反过来调它，所以接口放在这里、由 production 实现（架构 §3.3 第 2 种）。
 * 生产还没上线时没有实现，算料、待到料全按承诺交期倒推（NeedDates）
 */
public interface NeedDateSource {

    /** 几张订单各自的要料日期：它已下单、生产中的生产单里"裁剪"最早的计划开始；还没排单的不在返回里 */
    Map<Long, LocalDate> cutStartOf(Collection<Long> orderIds);
}
