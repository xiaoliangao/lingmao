package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.production.dto.SchedulePrefillVO;
import com.lingmao.scm.module.production.dto.ScheduleQuoteVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;

import java.util.List;

/**
 * 排单（docs/api/02 §3.12）：界面在订单详情，后端在 production（架构 §3.1）。订单要是已审核或生产中，不然 1002。
 * 生成草稿时先锁订单再查、再占已排产，和变更单取消订单排队
 */
public interface ScheduleService {

    SchedulePrefillVO prefill(long orderId);

    /** 一家工厂做这个款，每种加工方式的现价、能不能下单 */
    ScheduleQuoteVO quote(long orderId, long styleId, long factoryId);

    /** 一块生成一张生产单草稿，返回它们的 DocRef。超排 1004（data 是 ScheduleOverRow[]） */
    List<DocRef> schedule(ScheduleReq req);
}
