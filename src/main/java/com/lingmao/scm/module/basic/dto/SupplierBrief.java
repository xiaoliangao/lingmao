package com.lingmao.scm.module.basic.dto;

import lombok.Data;

/**
 * 物料导入导出要的供应商编码、名称：basic 不调 supplier（依赖方向是 supplier → basic），
 * 在自己的 Mapper XML 里只读查 sup_supplier（架构 §3.2 规则 4）。不看状态：默认供应商只当默认值（架构 A23）
 */
@Data
public class SupplierBrief {
    private Long id;
    private String code;
    private String name;
    private String shortName;
}
