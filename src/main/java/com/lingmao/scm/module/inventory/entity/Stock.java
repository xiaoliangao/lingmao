package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 库存：仓库 × SKU × 物权 × 货位（数据库设计 §13.3）。只有 StockLedger 用条件更新改 */
@Data
@TableName("inv_stock")
public class Stock {

    /** 物权：自有 */
    public static final String OWN = "OWN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long materialSkuId;

    private String ownerType;

    private Long ownerId;

    private Long locationId;

    private BigDecimal qty;

    /** 每次产生流水的同键过账递增，盘点用它检测包括净量不变的收发。 */
    private Long movementRevision;

    private OffsetDateTime updateTime;
}
