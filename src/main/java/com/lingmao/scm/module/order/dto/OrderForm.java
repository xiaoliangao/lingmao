package com.lingmao.scm.module.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 新建、保存草稿共用的列（前端 OrderForm，docs/api/02 §3.5）：整单覆盖，没填的发 null，明细发全部（一个 SKU 一行）。
 * 这里只查格式；资料包、款式状态在提交时查
 */
@Data
public abstract class OrderForm {

    @NotNull(message = "请选主体")
    private Long companyId;

    @NotBlank(message = "请选订单类型")
    private String orderType;

    /** TRADE 关着，发了就 400 */
    private Long customerId;

    @Size(max = 64, message = "客户订单号最多 64 个字")
    private String customerOrderNo;

    @Size(max = 64, message = "合同号最多 64 个字")
    private String contractNo;

    private String brand;

    @NotNull(message = "请填下单日期")
    private LocalDate orderDate;

    @NotNull(message = "请填要求交期")
    private LocalDate requiredDate;

    @NotNull(message = "请选业务员")
    private Long salesId;

    @NotNull(message = "请选跟单员")
    private Long merchId;

    @NotBlank(message = "请选币种")
    private String currency;

    @Size(max = 500, message = "送货要求最多 500 个字")
    private String deliveryReq;

    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;

    @NotNull(message = "缺少明细")
    private List<@Valid OrderItemReq> items = new ArrayList<>();
}
