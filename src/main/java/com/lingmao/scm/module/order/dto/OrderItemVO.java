package com.lingmao.scm.module.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 详情明细的一行 = 一个款式 SKU（前端 OrderItem） */
@Data
public class OrderItemVO {
    private Long id;
    private Integer seq;
    private Long styleId;
    private Long styleSkuId;
    /** 款号-颜色码-尺码 */
    private String skuCode;
    private Long colorId;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String sizeCode;
    private Integer qty;
    private BigDecimal price;
    private BigDecimal amount;
    private LocalDate lineDueDate;
    private Long techPackId;
    private Integer scheduledQty;
    private Integer returnedQty;
    private String remark;
}
