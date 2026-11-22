package com.lingmao.scm.module.supplier.dto;
import java.time.LocalDate;
/** 一份到期证照；不包含文件地址、账号或报价。 */
public record ExpiringCertVO(Long id, SupplierRowVO supplier, String certType, LocalDate validUntil) { }
