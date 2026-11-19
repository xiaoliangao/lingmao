package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 补货清单勾的行 → 备货采购单草稿（POST /api/purchase-orders/from-reorder） */
@Data
public class FromReorderReq {
    @NotEmpty(message = "请勾要补货的物料")
    @Size(max = 500, message = "一次最多 500 行")
    private List<@Valid ReorderPickItem> items;

    /** 品类线（RFC 013 §2）：生成的采购单都是这条线；只有一条线的人不用填 */
    private String productLine;
}
