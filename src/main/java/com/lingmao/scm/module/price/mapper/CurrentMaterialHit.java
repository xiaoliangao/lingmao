package com.lingmao.scm.module.price.mapper;

import com.lingmao.scm.module.price.entity.Price;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量取物料现价的一行结果：取到的那条价，加上它是给哪个 key 的。
 * 供应商、物料和价格那条一样；SKU 要另外带：key 问的是卡其，取到的可能是物料层的价（material_sku_id 是空）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CurrentMaterialHit extends Price {

    /** key 里的 SKU，空 = 只问物料层的价 */
    private Long keySkuId;
}
