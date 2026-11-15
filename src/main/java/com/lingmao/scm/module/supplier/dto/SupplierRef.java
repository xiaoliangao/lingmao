package com.lingmao.scm.module.supplier.dto;

import com.lingmao.scm.module.supplier.entity.SupplierStatus;

import java.math.BigDecimal;

/**
 * 给别的域用的供应商（RFC 004 §7）：单据、价格档案显示和带默认值。不带账号这些敏感的列。
 * 改动挂起期间这里还是旧值（PRD §6.8.2：挂起期间新单据用旧值）
 */
public record SupplierRef(Long id,
                          String code,
                          String name,
                          String shortName,
                          SupplierStatus status,
                          BigDecimal taxPct,
                          String settleMethod,
                          Integer creditDays,
                          String invoiceType) {
}
