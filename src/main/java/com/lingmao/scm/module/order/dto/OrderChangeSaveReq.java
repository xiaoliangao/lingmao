package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT /api/order-changes/{id}（前端 OrderChangeUpdateForm） */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderChangeSaveReq extends OrderChangeForm {
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
}
