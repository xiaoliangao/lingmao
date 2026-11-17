package com.lingmao.scm.module.supplier.dto;

import com.lingmao.scm.module.supplier.entity.SupplierStatus;

import java.math.BigDecimal;

/**
 * 给别的域用的供应商（RFC 004 §7）：单据、价格档案显示和带默认值。不带账号这些敏感的列。
 * buyerId 是负责这家的采购员（采购单带默认值），contact、phone 是联系人、电话（采购单打印）
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
                          String invoiceType,
                          Long buyerId,
                          String contact,
                          String phone) {
}
