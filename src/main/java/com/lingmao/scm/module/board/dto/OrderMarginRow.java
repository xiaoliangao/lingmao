package com.lingmao.scm.module.board.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 订单毛利的一行：销售额 − 按资料包核价估的成本（RFC 019 §3.4） */
@Data
public class OrderMarginRow {
    private Long id;
    private String no;
    private String brand;
    private String productLine;
    private LocalDate orderDate;
    private String status;
    private int totalQty;
    private String currency;
    /** 订单金额（明细金额合计） */
    private BigDecimal salesAmount;
    /** 成本：每行件数 × 那一版资料包的单件成本（核价），能算的加起来 */
    private BigDecimal costAmount;
    /** 算不了成本的件数（资料包没锁、核价算不出来）；> 0 时成本、毛利偏低偏高都可能，页面标出来 */
    private int costMissingQty;
    /** 销售额 − 成本；外币单是 null（没有汇率，不算） */
    private BigDecimal margin;
    /** 毛利 ÷ 销售额，百分数一位小数；销售额是 0、外币单时 null */
    private BigDecimal marginRate;
}
