package com.lingmao.scm.module.price.mapper;

import lombok.Value;

/**
 * 批量取物料现价的一行条件：MaterialPriceKey 加上这个物料现在的采购单位（basic 给的，当参数传进 SQL）。
 * 用 @Value 不用 record：MyBatis 按 getXxx 取参数
 */
@Value
public class MaterialUnitKey {
    long supplierId;
    long materialId;
    Long materialSkuId;
    String unit;
}
