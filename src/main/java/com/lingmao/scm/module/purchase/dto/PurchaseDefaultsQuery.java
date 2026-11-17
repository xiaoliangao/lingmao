package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 表单默认值（GET /api/purchase-orders/defaults，前端 PurchaseDefaultsQuery） */
@Data
public class PurchaseDefaultsQuery {
    @NotNull(message = "请选供应商")
    private Long supplierId;
    /** 取哪天的现价，不传 = 今天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDate;
    /** 表单上全部行的 SKU */
    private List<Long> skuIds;
}
