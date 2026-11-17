package com.lingmao.scm.module.inventory.dto;

/** 盘点范围选的分类（前端 CountCategory）：二级分类名，parentName 是一级 */
public record CountCategoryVO(Long id, String name, String parentName) {
}
