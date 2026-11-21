package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 订单进度总览的筛选（RFC 016 §1）：只看在手的需求订单（已审核、生产中） */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderProgressQuery extends PageQuery {

    /** 分段：ALL（默认）/ UNSCHEDULED 未排完 / NEAR 临期 / LATE 已逾期 / PRODUCTION 生产有风险 */
    @Pattern(regexp = "ALL|UNSCHEDULED|NEAR|LATE|PRODUCTION", message = "分段只能是 ALL、UNSCHEDULED、NEAR、LATE、PRODUCTION")
    private String segment;
    /** 订单号、款号；一次查几个时精确查 */
    private String keyword;
    /** 字典 brand */
    private String brand;
    /** 字典 order_type */
    private String orderType;
    private Long salesId;
    private Long merchId;
    /** 交期（承诺交期 ?? 要求交期，行交期更早的用行交期），两头都含 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateTo;
    private boolean mine;
}
