package com.lingmao.scm.module.inventory.entity;

/** 盘点范围：全部（这个仓有库存记录的 SKU）/ 按物料分类（inv_count_category）/ 手选物料 */
public enum CountScope {
    ALL,
    CATEGORY,
    PICKED
}
