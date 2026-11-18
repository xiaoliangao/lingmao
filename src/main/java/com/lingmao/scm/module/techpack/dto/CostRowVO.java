package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import lombok.Data;

import java.math.BigDecimal;

/** 核价的一行物料（前端 CostRow），从物料清单算出来，不存 */
@Data
public class CostRowVO {
    private Long bomItemId;
    private Integer seq;
    private String part;
    private Long materialId;
    private String materialName;
    /** 按这次算的颜色取到的物料 SKU；没配料是 null */
    private Long materialSkuId;
    private String skuCode;
    private String skuColorName;
    private String skuSpec;
    private MaterialSource source;
    /** 基码用量（统一用量的行就是统一用量），库存单位；没填是 0 */
    private BigDecimal usageQty;
    private String unit;
    private BigDecimal lossPct;
    /** 配料上单独的价 > 行上的价；都没有是 null */
    private BigDecimal price;
    private Boolean priceTaxIncluded;
    private Boolean priceFreightIncluded;
    /** 只有自购的行计入合计 */
    private boolean counted;
    /** 单件成本，两位小数；不计入的是 0 */
    private BigDecimal cost;
}
