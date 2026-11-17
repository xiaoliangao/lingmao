package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;

/** 列表里"款式"一列的一个款：缩略图 + 款号 */
public record OrderStyleRef(Long styleId, String styleCode, AttachmentVO image) {
}
