package com.lingmao.scm.module.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;

/** 款式详情"需求订单"页签的一行（前端 StyleOrderRow）：这张订单里这个款的数 */
@Data
public class StyleOrderRowVO {
    private Long orderId;
    private String no;
    private String status;
    private String orderType;
    private String brand;
    private Integer qty;
    /** 锁定的生产 BOM 版本号；审核前是 null */
    private Integer techPackVersion;
    private LocalDate requiredDate;
    private LocalDate commitDate;
    /** 取跟单员名字用，不返回 */
    @JsonIgnore
    private Long merchId;
    private String merchName;
}
