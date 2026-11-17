package com.lingmao.scm.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 导入时整批订单共用的单头（前端 OrderImportHead），拼在导入地址的查询参数里 */
@Data
public class OrderImportHead {

    @NotNull(message = "请选主体")
    private Long companyId;

    @NotBlank(message = "请选订单类型")
    private String orderType;

    private String brand;

    /** 行交期没填的用它 */
    @NotNull(message = "请填要求交期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate requiredDate;

    /** 不给 = 导入的人 */
    private Long salesId;
    private Long merchId;
}
