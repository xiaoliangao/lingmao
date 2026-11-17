package com.lingmao.scm.module.price.dto;

import com.lingmao.scm.module.biz.entity.BizType;

/** 单据审核时记价的来源单据（RFC 006 §5）：价格档案的 source_type 存 type 的名字，source_no 冗余单号 */
public record PriceSource(BizType type, long id, String no) {
}
