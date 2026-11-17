package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 算料单勾的行 → 采购单草稿（POST /api/purchase-orders/from-mrp）。version 是算料单的 */
@Data
public class FromMrpReq {
    @NotNull(message = "请选算料单")
    private Long calcId;
    @NotEmpty(message = "请勾要生成采购单的行")
    private List<Long> itemIds;
    @NotNull(message = "缺少版本号，请刷新")
    private Integer version;
}
