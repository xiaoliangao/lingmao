package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** GET /api/order-changes/{id}（前端 OrderChangeDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderChangeDetailVO extends DocHeaderVO {
    private Long orderId;
    private String orderNo;
    private String orderStatus;
    private List<ChangeType> changeTypes = List.of();
    private boolean cancel;
    private String reason;
    private ResponsibleParty responsibleParty;
    private String feeNote;
    private String rejectReason;
    private String summary;
    private List<ChangeItemVO> items = List.of();
}
