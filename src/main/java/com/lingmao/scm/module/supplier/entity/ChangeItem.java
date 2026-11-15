package com.lingmao.scm.module.supplier.entity;

/**
 * 待批改动里的一项（sup_supplier_change.changes 的数组元素）：哪个字段、叫什么、改前、改后。
 * 值都存成文字、存原值（字典存 value、账号存全号）：批准时要核对 before 还等于当前值，再把 after 写回去（RFC 004 §4）。
 * 给页面看的时候再翻成字典文字、遮账号（ChangeVO）
 *
 * @param field  字段名，SupplierKeyField 的 field，如 creditDays
 * @param label  中文名，如 账期
 * @param before 改前；空是 null
 * @param after  改后；清空是 null
 */
public record ChangeItem(String field, String label, String before, String after) {
}
