package com.lingmao.scm.module.supplier.entity;

import lombok.Data;

import java.time.LocalDate;

/** 列表上"证照"一列：一家有几份证照、最早到期的是哪天（长期有效的不算）。SupplierMapper.selectCertSummaries 查出来 */
@Data
public class CertSummary {
    private Long supplierId;
    private Integer certCount;
    /** 最早的有效期；全是长期有效的为空 */
    private LocalDate earliestUntil;
}
