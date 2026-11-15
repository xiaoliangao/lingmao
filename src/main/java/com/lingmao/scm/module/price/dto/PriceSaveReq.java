package com.lingmao.scm.module.price.dto;

import com.lingmao.scm.module.price.entity.PriceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 录一条价格（POST /api/prices，RFC 006 §3）。
 * 物料价填 materialId（materialSkuId 选填，空 = 所有颜色同价）；加工价填 styleId、processMode。不属于这一类的列不收。
 * 单位不收：物料价取物料现在的采购单位，加工价是件。税率不填带供应商的；含税、含运不填算"是"；生效日期不填是今天
 */
@Data
public class PriceSaveReq {

    @NotNull(message = "请选价格类型：物料价还是加工价")
    private PriceType type;

    @NotNull(message = "请选供应商（加工价选工厂）")
    private Long supplierId;

    private Long materialId;

    private Long materialSkuId;

    private Long styleId;

    /** 字典 process_mode 的 value */
    @Size(max = 64, message = "加工方式不对")
    private String processMode;

    @NotNull(message = "请填单价")
    @DecimalMin(value = "0", inclusive = false, message = "单价要大于 0")
    @Digits(integer = 10, fraction = 4, message = "单价最多 4 位小数")
    private BigDecimal price;

    @DecimalMin(value = "0", message = "税率不能小于 0")
    @DecimalMax(value = "100", message = "税率不能大于 100")
    @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数")
    private BigDecimal taxPct;

    private Boolean taxIncluded;

    private Boolean freightIncluded;

    private LocalDate effectiveDate;

    @Size(max = 255, message = "调价原因最多 255 个字")
    private String reason;

    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
