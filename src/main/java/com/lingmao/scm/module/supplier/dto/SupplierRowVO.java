package com.lingmao.scm.module.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 供应商列表的一行。不出账号（RFC 004 §5） */
@Data
public class SupplierRowVO {
    private Long id;
    private String code;
    private String name;
    private String shortName;
    private String creditCode;
    private String level;
    /** 做哪几条品类线，字典 product_line 的 value，按字典顺序 */
    private List<String> productLines;
    private SupplierStatus status;
    /** 字典 supplier_category 的 value，按字典顺序 */
    private List<String> categories;
    private String contact;
    private String phone;
    private String settleMethod;
    private Integer creditDays;
    private BigDecimal taxPct;
    private Long buyerId;
    private String buyerName;
    /** 有一份改动等审批 */
    private boolean changePending;
    /** 几份证照 */
    private int certCount;
    /** 最早哪天到期；全是长期有效的、没有证照的为空 */
    private LocalDate certEarliestUntil;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
