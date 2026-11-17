package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** BOM 行的一条配料（tp_bom_item_sku，数据库设计 §9.3）：这个款色、这个尺码用哪个物料 SKU；固定的两个都空 */
@Data
@TableName("tp_bom_item_sku")
public class BomItemSku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bomItemId;

    /** 款色（bas_color）；固定、随尺码的是 null */
    private Long colorId;

    /** 尺码；固定、随款色的是 null */
    private String sizeCode;

    private Long materialSkuId;

    /** 这个 SKU 单独的参考单价，元 / 库存单位；null = 用行上的 */
    private BigDecimal refPrice;
}
