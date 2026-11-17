package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 物料清单的一行（tp_bom_item，数据库设计 §9.2）：一个部位用的一种物料。用哪个物料 SKU 在 BomItemSku，分码用量在 BomItemSize。
 * 明细跟着资料包整版覆盖，没有审计列
 */
@Data
@TableName("tp_bom_item")
public class BomItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long techPackId;

    /** 行号，从 1 起 */
    private Integer seq;

    /** 部位，字典 bom_part */
    private String part;

    private Long materialId;

    private BomSkuMode skuMode;

    private BomUsageMode usageMode;

    /** 统一用量，库存单位；分码的行是 null */
    private BigDecimal usageQty;

    /** 物料的库存单位，存一份 */
    private String unit;

    /** 损耗率 %，3 表示 3% */
    private BigDecimal lossPct;

    private MaterialSource source;

    private Long supplierId;

    /** 参考单价，元 / 库存单位 */
    private BigDecimal refPrice;

    /** 默认单价取自哪条价格档案 */
    private Long priceSourceId;

    private Boolean priceTaxIncluded;

    private Boolean priceFreightIncluded;

    private String remark;
}
