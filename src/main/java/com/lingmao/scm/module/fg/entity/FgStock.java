package com.lingmao.scm.module.fg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 成品库存：仓库 × 款式 SKU × 货位（0 = 不分）。只有 FgLedger 用条件更新改 */
@Data
@TableName("fg_stock")
public class FgStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long styleSkuId;

    private Long locationId;

    private Integer qty;

    private OffsetDateTime updateTime;
}
