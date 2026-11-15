package com.lingmao.scm.module.style.dto;

/**
 * 款式上的一个颜色（详情的颜色尺码页签）。status 是在这个款式上停没停用；colorStatus 是基础资料里的颜色停没停用。
 * skuCount 大于 0 的只能停用，不能去掉（RFC 005 §5）
 */
public record StyleColorVO(Long colorId,
                           String code,
                           String name,
                           String hex,
                           Integer seq,
                           Integer status,
                           Integer colorStatus,
                           int skuCount) {
}
