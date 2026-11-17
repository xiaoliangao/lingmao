package com.lingmao.scm.module.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 存草稿（前端 CountForm）：整体覆盖，items 按 SKU 对上已有的行改实盘数、备注；items 里没有的行删掉 */
@Data
public class CountSaveReq {
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
    @NotNull(message = "请填盘点日期")
    private LocalDate countDate;
    @NotNull(message = "请选盘点人")
    private Long counterId;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    @NotNull(message = "缺少明细")
    private List<@Valid Item> items;

    /** 前端 CountItemForm */
    @Data
    public static class Item {
        @NotNull(message = "明细缺少物料")
        private Long materialSkuId;
        @DecimalMin(value = "0", message = "实盘数不能是负数")
        @Digits(integer = 11, fraction = 3, message = "实盘数最多 3 位小数")
        private BigDecimal countedQty;
        @Size(max = 255, message = "备注最多 255 个字")
        private String remark;
    }
}
