package com.lingmao.scm.module.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 新建、改草稿共用的单头 + 明细（前端 InvDocForm 去掉 version） */
@Data
public class InvDocForm {
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "请填日期")
    private LocalDate docDate;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    /** 退料入库由工厂仓带出，发什么都按仓库的；别的手建单没有往来 */
    private Long partnerId;
    @Size(max = 64, message = "原因最多 64 个字")
    private String reason;
    @Size(max = 64, message = "送货单号最多 64 个字")
    private String supplierDeliveryNo;
    @Size(max = 64, message = "物流单号最多 64 个字")
    private String logisticsNo;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    @NotEmpty(message = "明细至少一行")
    private List<@Valid InvDocItemReq> items;
}
