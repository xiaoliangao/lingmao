package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 订单毛利（GET /api/reports/order-margins，RFC 019 §3.4）：已审核以后的需求订单，按下单日期 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderMarginQuery extends PageQuery {
    @NotNull(message = "请选开始日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    @NotNull(message = "请选结束日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    /** 查订单号、客户订单号、品牌 */
    private String keyword;
    /** 品类线，字典 product_line */
    private String productLine;
}
