package com.lingmao.scm.module.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 排单（POST /api/production-orders/schedule，前端 ScheduleReq）：一块生成一张生产单草稿 */
@Data
public class ScheduleReq {
    @NotNull(message = "缺少 orderId")
    private Long orderId;
    @NotEmpty(message = "至少排一块")
    @Valid
    private List<Block> blocks;

    @Data
    public static class Block {
        @NotNull(message = "缺少 styleId")
        private Long styleId;
        @NotNull(message = "请选加工厂")
        private Long factoryId;
        @NotBlank(message = "请选加工方式")
        private String processMode;
        /** 没填由 Service 报 1000（PRD 写的是"单价没填"拦在确定时，一块一块说清楚） */
        private BigDecimal unitPrice;
        private Long priceSourceId;
        @Size(max = 255, message = "改价原因最长 255 个字")
        private String priceChangeReason;
        @NotNull(message = "请填交期")
        private LocalDate dueDate;
        @NotNull(message = "请选生产跟单")
        private Long merchId;
        private String settleMethod;
        @Size(max = 2000, message = "条款最长 2000 个字")
        private String terms;
        @Size(max = 500, message = "备注最长 500 个字")
        private String remark;
        @NotNull(message = "明细不能为空")
        @Valid
        private List<Item> items;
    }

    @Data
    public static class Item {
        @NotNull(message = "缺少 orderItemId")
        private Long orderItemId;
        @NotNull(message = "请填件数")
        @Min(value = 0, message = "件数不能是负数")
        private Integer qty;
    }
}
