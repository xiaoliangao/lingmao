package com.lingmao.scm.module.supplier.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商分类的一行（sup_supplier_category，数据库设计 §6.2）：主键是 (supplier_id, category)，没有 id，
 * 不走 BaseMapper，读写都是 SupplierMapper 里写好的 SQL。保存时整批删了重插
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierCategoryRow {
    private Long supplierId;
    /** 字典 supplier_category 的 value */
    private String category;
}
