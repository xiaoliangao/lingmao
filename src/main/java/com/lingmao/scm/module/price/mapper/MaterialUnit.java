package com.lingmao.scm.module.price.mapper;

import lombok.Value;

/** 物料现在的采购单位（basic 给的）：只看现价时，物料价只认这个单位的记录 */
@Value
public class MaterialUnit {
    long materialId;
    String unit;
}
