package com.lingmao.scm.module.board.service;

import com.lingmao.scm.module.board.dto.OrderReview;

/** 需求订单审核对话框的评审信息（docs/api/02 §3.8）：资料包、缺料粗算、默认加工厂在手 */
public interface OrderReviewService {

    /** 看得到这张订单（含轮到我审）就能取，不然 404 */
    OrderReview review(long orderId);
}
