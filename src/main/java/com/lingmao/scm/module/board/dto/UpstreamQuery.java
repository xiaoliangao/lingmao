package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.entity.BizType;
import lombok.Data;

/** 上游已变更（GET /api/board/upstream，docs/api/06 §2.5）：mine、ownerId 同 HomeTodoQuery */
@Data
public class UpstreamQuery {
    /** PRODUCTION_ORDER / PURCHASE_ORDER / MRP_CALC；不传是全部 */
    private BizType type;
    /** true：只看负责人是我的 */
    private boolean mine;
    /** 只看负责人是这个人的（生产跟单、采购员……的用户 id），和 mine 二选一；数据范围不是"全部"的人传了也只看自己的 */
    private Long ownerId;
}
