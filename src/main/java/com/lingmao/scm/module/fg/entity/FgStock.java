package com.lingmao.scm.module.fg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 成品库存：主体 × 仓库 × 款式 SKU × 货位（0 = 不分）。只有 FgLedger 用条件更新改 */
@Data
@TableName("fg_stock")
public class FgStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long warehouseId;

    private Long styleSkuId;

    private Long locationId;

    private Integer qty;

    /** 每次实际库存变动加一；读库存不会改它。 */
    private Long movementRevision;

    private OffsetDateTime updateTime;
}
