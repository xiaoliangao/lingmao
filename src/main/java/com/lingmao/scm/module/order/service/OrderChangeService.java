package com.lingmao.scm.module.order.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.order.dto.OrderChangeCreateReq;
import com.lingmao.scm.module.order.dto.OrderChangeDetailVO;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderChangeRowVO;
import com.lingmao.scm.module.order.dto.OrderChangeSaveReq;

import java.util.Map;

/**
 * 订单变更单（docs/api/02 §3.14）：表单发的是改完以后的样子，这里和订单现在的数比出改了哪几处。单级审核，审过写回订单、
 * 给下游单据发变更提醒。数据范围跟着订单走，再加变更单的制单人
 */
public interface OrderChangeService {

    PageResult<OrderChangeRowVO> page(OrderChangeQuery query);

    /** 各状态几张；DRAFT 是全部草稿，另外 REJECTED 是其中被驳回的 */
    Map<String, Long> counts(OrderChangeQuery query);

    OrderChangeDetailVO detail(long id);

    /** 新建草稿：先跑一遍提交的检查，免得留下提交不了的草稿 */
    DocSaved create(OrderChangeCreateReq req);

    /** 改草稿（被驳回的改了再提交），返回新的 version */
    int update(long id, OrderChangeSaveReq req);

    void delete(long id);

    /** submit、withdraw、reject、audit、void */
    void act(long id, String action, DocActionReq req);

    void checkVisible(long id);

    /** 改附件前查：在本人数据范围里，不放"审核不受数据范围限制"（只能审，不能改）；不在 → 404 */
    void checkEditable(long id);

    /** 原成功操作的来源对象仍须在当前可见范围内；不重验可生成状态。 */
    void checkSourceOrderVisible(long orderId);
}
