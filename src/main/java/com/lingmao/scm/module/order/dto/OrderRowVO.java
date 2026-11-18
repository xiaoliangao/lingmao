package com.lingmao.scm.module.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.order.entity.OrderSource;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 需求订单列表的一行（前端 OrderRow）。款式、人名、按钮由 Service 填 */
@Data
public class OrderRowVO {
    private Long id;
    private String no;
    private String status;
    private String orderType;
    private String brand;
    private String contractNo;
    private LocalDate orderDate;
    private LocalDate requiredDate;
    private LocalDate commitDate;
    private String currency;
    private Integer totalQty;
    private BigDecimal totalAmount;
    private int scheduledQty;
    private int returnedQty;
    private List<OrderStyleRef> styles = List.of();
    private Long salesId;
    private String salesName;
    private Long merchId;
    private String merchName;
    private String companyName;
    private OrderSource source;
    /** 制单人 id：取 createByName、算撤回按钮用，不返回 */
    @JsonIgnore
    private Long createBy;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private Integer version;
    private List<String> actions = List.of();
}
