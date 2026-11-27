package com.lingmao.scm.module.board.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 物料供应商的采购到货及时率（RFC 019 §3.1）：一行一个供应商，合计行 supplierId 是 null。
 * 按采购行算：到齐的那天（入库累计够了；单子完成、结案了按最后一次入库）不晚于约定交期 = 准时；
 * 过了交期还没到齐 = 逾期；交期还没到、也没到齐的不算
 */
@Data
public class PurchasePerfRow {
    private Long supplierId;
    private String supplierCode;
    private String supplierShortName;
    /** 算进来的行（准时 + 逾期） */
    private int lineCount;
    private int onTimeCount;
    private int lateCount;
    /** 交期在这段时间里、还没到期也没到齐的行（不算进及时率） */
    private int pendingCount;
    /** 准时 ÷ 算进来的，百分数一位小数；没有算进来的是 null */
    private BigDecimal onTimeRate;
    /** 逾期的行平均晚几天（没到齐的算到今天），一位小数；没有逾期是 null */
    private BigDecimal avgLateDays;
}
