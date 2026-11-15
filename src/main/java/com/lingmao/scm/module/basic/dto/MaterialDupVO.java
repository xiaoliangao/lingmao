package com.lingmao.scm.module.basic.dto;

import lombok.Data;

/** 建档查重的一条（GET /api/materials/dup-check，只提示不拦）：reason 是"同分类同名"或"同供应商同货号" */
@Data
public class MaterialDupVO {
    private Long id;
    private String code;
    private String name;
    private String supplierItemNo;
    private String defaultSupplierName;
    private String reason;
}
