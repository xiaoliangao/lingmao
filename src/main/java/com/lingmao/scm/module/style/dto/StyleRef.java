package com.lingmao.scm.module.style.dto;

/**
 * 给别的域显示用的款式（价格档案、需求订单的列表）：id、款号、名称。
 * 能不能选进单据看 StyleService.checkUsable，不看这里
 */
public record StyleRef(Long id, String code, String name) {
}
