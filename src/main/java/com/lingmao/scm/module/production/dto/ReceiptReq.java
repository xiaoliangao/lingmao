package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 登记回货（POST /api/production-orders/{id}/receipts，前端 ReceiptForm）：qty 是 0 的行不发 */
@Data
public class ReceiptReq {
    @NotNull(message = "缺少 clientActionId，请刷新后重新操作")
    private UUID clientActionId;
    @NotNull(message = "请填回货日期")
    private LocalDate receiptDate;
    private Long warehouseId;
    @Size(max = 64, message = "物流单号最长 64 个字")
    private String logisticsNo;
    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
    @Valid
    @Size(max = 9, message = "照片最多 9 张")
    private List<AttachmentReq> photos = List.of();
    @NotNull(message = "明细不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "缺少 prdOrderItemId")
        private Long prdOrderItemId;
        @NotNull(message = "请填回货数")
        @Min(value = 0, message = "回货数不能是负数")
        private Integer qty;
        @Min(value = 0, message = "次品数不能是负数")
        private int defectQty;
        @Size(max = 255, message = "备注最长 255 个字")
        private String remark;
    }
}
