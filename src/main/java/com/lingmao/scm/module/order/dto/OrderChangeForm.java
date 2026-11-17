package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.order.entity.ResponsibleParty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 提变更、改草稿共用的列（前端 OrderChangeForm，docs/api/02 §3.14）：发的是改完以后的样子，不是差异。
 * 后端拿它和订单现在的数比，一样的不算改动；取消订单时下面三组是空的
 */
@Data
public abstract class OrderChangeForm {

    private boolean cancel;

    @NotBlank(message = "请写变更原因")
    @Size(max = 500, message = "原因最多 500 个字")
    private String reason;

    @NotNull(message = "请选责任方")
    private ResponsibleParty responsibleParty;

    @Size(max = 500, message = "费用说明最多 500 个字")
    private String feeNote;

    /** 新的要求交期；不改发 null */
    private LocalDate requiredDate;

    /** 矩阵里每一格的新件数（没改的也发）；0 = 不要这个 SKU 了 */
    @NotNull(message = "缺少件数")
    private List<@Valid Qty> quantities = new ArrayList<>();

    /** 要改单价的款 */
    @NotNull(message = "缺少单价")
    private List<@Valid Price> prices = new ArrayList<>();

    /** 要换资料包版本的款 */
    @NotNull(message = "缺少资料包版本")
    private List<@Valid TechPack> techPacks = new ArrayList<>();

    /** 一个 SKU 的新件数（前端 ChangeQtyForm） */
    public record Qty(@NotNull(message = "缺少 SKU") Long styleSkuId,
                      @NotNull(message = "请填件数") @PositiveOrZero(message = "件数不能是负数") Integer qty) {
    }

    /** 一个款的新单价，这个款的每个 SKU 都改成它（前端 ChangePriceForm） */
    public record Price(@NotNull(message = "缺少款式") Long styleId,
                        @NotNull(message = "请填单价") @PositiveOrZero(message = "单价不能是负数") BigDecimal price) {
    }

    /** 一个款换到哪一版生产 BOM（前端 ChangeTechPackForm） */
    public record TechPack(@NotNull(message = "缺少款式") Long styleId,
                           @NotNull(message = "请选资料包版本") Long techPackId) {
    }
}
