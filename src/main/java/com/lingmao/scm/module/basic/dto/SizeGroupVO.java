package com.lingmao.scm.module.basic.dto;

import java.util.List;

/** 尺码组和它的尺码（按顺序）。/options 也是这个样子，只有启用的组 */
public record SizeGroupVO(Long id, String name, String baseSize, Integer status, List<String> sizes) {
}
