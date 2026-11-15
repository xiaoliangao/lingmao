package com.lingmao.scm.module.supplier.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 擅长品类的一行（sup_supplier_good_at，数据库设计 §6.3）。没有 id，读写走 SupplierMapper 的 SQL，保存时整批删了重插。
 * categoryName 只在查询时带出来（JOIN bas_category 取显示用的名字，架构 §3.2 第 4 条），写的时候不用
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierGoodAtRow {
    private Long supplierId;
    private Long categoryId;
    private String categoryName;

    public SupplierGoodAtRow(Long supplierId, Long categoryId) {
        this(supplierId, categoryId, null);
    }
}
