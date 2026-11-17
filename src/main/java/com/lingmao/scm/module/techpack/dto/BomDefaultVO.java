package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一行 BOM 的默认值（前端 BomDefault，docs/api/01 §3.9）。单价都换算成元 / 库存单位；
 * 生产 BOM 先取价格档案里这家供应商的现价，没有带物料 SKU 单价，再没有带物料参考单价；研发 BOM 不看价格档案
 */
@Data
public class BomDefaultVO {
    private Long materialId;
    private String unit;
    private String purchaseUnit;
    /** 1 采购单位 = n 库存单位 */
    private BigDecimal conversionFactor;
    private Long supplierId;
    private String supplierName;
    private BigDecimal lossPct;
    private MaterialSource source;
    private BigDecimal refPrice;
    private Long priceSourceId;
    private boolean priceTaxIncluded;
    private boolean priceFreightIncluded;
    /** ARCHIVE 价格档案现价 / SKU 物料 SKU 单价 / MATERIAL 物料参考单价 / NONE 都没有 */
    private String priceFrom;
    /** 单价和行上不一样的 SKU，写进配料的 refPrice */
    private List<SkuPrice> skuPrices;

    public record SkuPrice(Long materialSkuId, BigDecimal refPrice) {
    }
}
