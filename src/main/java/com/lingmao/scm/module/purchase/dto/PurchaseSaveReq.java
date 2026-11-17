package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 改草稿（PUT /api/purchase-orders/{id}）：整体覆盖 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseSaveReq extends PurchaseForm {
    @NotNull(message = "缺少版本号，请刷新")
    private Integer version;
}
