package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT /api/orders/{id}（前端 OrderUpdateForm）：多一个打开时拿到的版本号 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderSaveReq extends OrderForm {
    /** 用户有意把草稿换到另一个主体时，带上原来的主体。 */
    private Long expectedCompanyId;
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
}
