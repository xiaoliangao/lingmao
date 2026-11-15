package com.lingmao.scm.module.style.dto;

/**
 * 新建款式的结果。code 是实际取到的款号：和预览的不一样（同一时间别人也建了）时，前端提示一句（RFC 005 §3.1）。
 * skuCount 是建款时生成的 SKU 个数
 */
public record StyleCreateResult(Long id, String code, int skuCount) {
}
