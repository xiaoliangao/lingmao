package com.lingmao.scm.module.fg.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 由回货单生成（docs/api/07 §3.3.3）。单头没填的按带出的默认值；items 是 null = 每行按还没入的全入（批量入库），
 * 给了就按给的，0 的行不建。post = true 建完马上审核过账，要 fg:doc:audit
 */
@Data
public class FgFromReceiptReq {
    @NotNull(message = "缺少 clientActionId，请刷新后重新操作")
    private UUID clientActionId;
    private boolean post;
    private LocalDate docDate;
    private Long warehouseId;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    @Size(max = 2000, message = "一张单最多 2000 行")
    private List<@Valid Item> items;

    @Data
    public static class Item {
        @NotNull(message = "缺少回货明细")
        private Long prdReceiptItemId;
        @NotNull(message = "要填件数")
        @Min(value = 0, message = "件数不能是负数")
        @Max(value = 99_999_999, message = "件数太大了")
        private Integer qty;
    }
}
