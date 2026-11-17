package com.lingmao.scm.module.price.dto;

import java.util.List;

/**
 * recordFromDoc 的结果：记了哪几条；哪几行因为物料后来改了采购单位没记（单据写进操作记录）。
 * 和现价一样的行不记，也不在 unitChanged 里
 */
public record DocPrices(List<PriceRef> recorded, List<PriceLine> unitChanged) {
}
