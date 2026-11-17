package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 保存时一行 BOM（前端 BomItemForm）。部位、物料必填在 Service 里按行号报；单位不用传，后端取物料的库存单位 */
@Data
public class BomItemForm {
    @Size(max = 64, message = "部位最长 64 个字")
    private String part;
    private Long materialId;
    private BomSkuMode skuMode;
    private BomUsageMode usageMode;
    private BigDecimal usageQty;
    private Map<String, BigDecimal> sizeUsages;
    private BigDecimal lossPct;
    private MaterialSource source;
    private Long supplierId;
    private BigDecimal refPrice;
    private Long priceSourceId;
    private Boolean priceTaxIncluded;
    private Boolean priceFreightIncluded;
    @Size(max = 255, message = "物料清单的备注最长 255 个字")
    private String remark;
    @Valid
    private List<BomSkuForm> skus;
}
