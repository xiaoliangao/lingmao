package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.entity.BizType;
import lombok.Data;

/** 上游已变更（GET /api/board/upstream，docs/api/06 §2.5）：mine、ownerId 同 HomeTodoQuery */
@Data
public class UpstreamQuery {
    /** PRODUCTION_ORDER / PURCHASE_ORDER / MRP_CALC；不传是全部 */
    private BizType type;
    private boolean mine;
    private Long ownerId;
}
