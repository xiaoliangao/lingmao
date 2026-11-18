package com.lingmao.scm.module.price.dto;

/** 批量读取加工现价的完整业务键；日期由整批调用统一指定。 */
public record ProcessPriceKey(long supplierId, long styleId, String processMode) { }
