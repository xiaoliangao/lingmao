package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 库存日志（数据库设计 §13.4）：每次变动一行，只插不改。来源二选一：入库单 / 出库单和明细，或盘点单和明细。
 * 反审核再记一条反方向的，不删原来的
 */
@Data
@TableName("inv_stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long invDocId;

    private Long invDocItemId;

    private Long countId;

    private Long countItemId;

    private StockChangeType changeType;

    private String docNo;

    /** 单据的实际发生日期 */
    private LocalDate bizDate;

    private Long warehouseId;

    private Long materialSkuId;

    private String ownerType;

    private Long ownerId;

    private Long locationId;

    /** 入为正、出为负 */
    private BigDecimal changeQty;

    /** 变动后结存 */
    private BigDecimal balanceQty;

    private String dyeLot;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
