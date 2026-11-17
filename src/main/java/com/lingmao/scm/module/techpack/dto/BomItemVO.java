package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 物料清单的一行（前端 BomItem） */
@Data
public class BomItemVO {
    private Long id;
    private Integer seq;
    private String part;
    private Long materialId;
    private String materialCode;
    private String materialName;
    /** 字典 material_type；FABRIC、LINING 进核价的面料块 */
    private String materialType;
    private Integer materialStatus;
    private BomSkuMode skuMode;
    private BomUsageMode usageMode;
    private BigDecimal usageQty;
    /** 分码用量：尺码 → 用量；统一的行是 {} */
    private Map<String, BigDecimal> sizeUsages;
    private String unit;
    private BigDecimal lossPct;
    private MaterialSource source;
    private Long supplierId;
    /** 供应商简称 */
    private String supplierName;
    private BigDecimal refPrice;
    private Long priceSourceId;
    private Boolean priceTaxIncluded;
    private Boolean priceFreightIncluded;
    private String remark;
    private List<BomSkuVO> skus;
    /** 单件成本 = 基码用量 ×（1 + 损耗率）× 单价；工厂自备、客供的行和算不出来的是 null。不受看成本权限管（架构 §4.9） */
    private BigDecimal unitCost;
    /** 第一个配料 SKU 的图，没有用物料主图 */
    private AttachmentVO image;
}
