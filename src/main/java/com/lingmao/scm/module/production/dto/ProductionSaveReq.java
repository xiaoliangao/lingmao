package com.lingmao.scm.module.production.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 改草稿（PUT /api/production-orders/{id}，前端 ProductionForm）：单头整体覆盖，明细不能改 */
@Data
public class ProductionSaveReq {
    private Long expectedCompanyId;
    @NotNull(message = "缺少 version")
    private Integer version;
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "请选加工厂")
    private Long factoryId;
    @NotBlank(message = "请选加工方式")
    private String processMode;
    @DecimalMin(value = "0", inclusive = false, message = "单价要大于 0")
    @Digits(integer = 10, fraction = 4, message = "单价最多 4 位小数")
    private BigDecimal unitPrice;
    private Long priceSourceId;
    @Size(max = 255, message = "改价原因最长 255 个字")
    private String priceChangeReason;
    @NotNull(message = "请填交期")
    private LocalDate dueDate;
    @NotNull(message = "请选生产跟单")
    private Long merchId;
    @NotBlank(message = "请选结算方式")
    private String settleMethod;
    @NotNull(message = "请填税率")
    @DecimalMin(value = "0", message = "税率不能是负数")
    @DecimalMax(value = "100", message = "税率最多 100%")
    private BigDecimal taxPct;
    private boolean taxIncluded;
    @Size(max = 2000, message = "条款最长 2000 个字")
    private String terms;
    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
    /** 加哪些按单选节点（编码，如 SECONDARY 二次加工）：审核时和每张单都有的节点一起倒排；null、[] = 不加（甲方 11-27 答 Q61） */
    @Size(max = 10, message = "最多加 10 个节点")
    private List<String> optionalNodes;
}
