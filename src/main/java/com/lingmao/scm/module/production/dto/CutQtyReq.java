package com.lingmao.scm.module.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 登记裁床数（docs/api/05 §3.6.3，RFC 013 §4）：按 SKU 填，没变的行可以不发；cutQty 为 null = 清掉 */
@Data
public class CutQtyReq {
    @NotEmpty(message = "一个数都没填")
    @Size(max = 2000, message = "明细太多了")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "缺少 prdOrderItemId")
        private Long prdOrderItemId;
        @PositiveOrZero(message = "裁床数不能小于 0")
        private Integer cutQty;
    }
}
