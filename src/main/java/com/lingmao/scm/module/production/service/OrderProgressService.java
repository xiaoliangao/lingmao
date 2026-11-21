package com.lingmao.scm.module.production.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.dto.OrderProgressRowVO;

/** 订单进度总览（RFC 016 §1，PRD §6.5.7 的 06.07）：页面在订单里，后端在 production（和排单一样） */
public interface OrderProgressService {

    PageResult<OrderProgressRowVO> page(OrderProgressQuery query);

    /** 分段上的数：不看 segment */
    OrderProgressCounts counts(OrderProgressQuery query);
}
