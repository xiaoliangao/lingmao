package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** POST /api/order-changes（前端 OrderChangeCreateForm）：哪张订单、变更依据附件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderChangeCreateReq extends OrderChangeForm {

    @NotNull(message = "缺少订单")
    private Long orderId;

    private List<@Valid AttachmentReq> files = new ArrayList<>();
}
