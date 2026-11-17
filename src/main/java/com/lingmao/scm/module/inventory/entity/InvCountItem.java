package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 盘点明细（数据库设计 §13.6）：一张单一个 SKU 一行。差异 = 实盘 − 账面，现算不存 */
@Data
@TableName("inv_count_item")
public class InvCountItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long countId;

    private Integer seq;

    private Long materialSkuId;

    private String ownerType;

    private Long ownerId;

    /** 账面数：建单（或重新取账面数）时的库存快照 */
    private BigDecimal bookQty;

    /** 实盘数，还没盘是 null */
    private BigDecimal countedQty;

    private String remark;
}
