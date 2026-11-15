package com.lingmao.scm.module.supplier.entity;

import lombok.Data;

/**
 * 品类树上的一个节点，只有导入时按名称匹配擅长品类要用（RFC 004 §9）：id、上级、名字、第几级。
 * 读的是 basic 的 bas_category，只读、只取这几列（SupplierMapper.selectEnabledCategories）。
 * basic 的 CategoryService 和这一步并行在写，第 3 步看它有没有按名称取的方法，有就改调它（RFC 004 §17）
 */
@Data
public class CategoryName {
    private Long id;
    private Long parentId;
    private String name;
    private Integer level;
}
