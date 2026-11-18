package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT /api/orders/{id}（前端 OrderUpdateForm）：多一个打开时拿到的版本号 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderSaveReq extends OrderForm {
    /** Explicit original company when the user intentionally moves a draft to another company. */
    private Long expectedCompanyId;
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
}
