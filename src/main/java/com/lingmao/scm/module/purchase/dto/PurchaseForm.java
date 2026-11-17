package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.purchase.entity.PurchaseType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购单的表单（前端 PurchaseForm），新建、改草稿共用。附件建好以后另传 */
@Data
public class PurchaseForm {
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "请选供应商")
    private Long supplierId;
    @NotNull(message = "请选采购类型")
    private PurchaseType purchaseType;
    @NotNull(message = "请选采购员")
    private Long buyerId;
    @NotNull(message = "请填下单日期")
    private LocalDate orderDate;
    /** null = 下单日 + 各行物料采购周期最长的（没设按 7 天） */
    private LocalDate deliveryDate;
    @NotNull(message = "请选交货地点")
    private Long warehouseId;
    @Size(max = 64, message = "结算方式不对")
    private String settleMethod;
    @DecimalMin(value = "0", message = "税率不能小于 0")
    @DecimalMax(value = "100", message = "税率不能大于 100")
    @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数")
    private BigDecimal taxPct;
    private boolean taxIncluded = true;
    private boolean freightIncluded = true;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    /** 计划用于款号：只有备货采购收 */
    private List<Long> planStyleIds;
    @NotNull(message = "请加明细")
    @Size(max = 500, message = "一张采购单最多 500 行")
    private List<@Valid PurchaseItemReq> items;
}
