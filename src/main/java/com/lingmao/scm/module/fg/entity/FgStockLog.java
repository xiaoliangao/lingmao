package com.lingmao.scm.module.fg.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 成品库存日志：每次变动一行，只插不改；反审核再记一条反方向的 */
@Data
@TableName("fg_stock_log")
public class FgStockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fgDocId;

    /** 单据的 doc_type */
    private FgDocType changeType;

    private String docNo;

    private LocalDate bizDate;

    private Long warehouseId;

    private Long styleSkuId;

    private Long locationId;

    /** 入正出负 */
    private Integer changeQty;

    /** 变动后结存 */
    private Integer balanceQty;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
