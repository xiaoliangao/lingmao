package com.lingmao.scm.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 变更明细（数据库设计 §10.4）：一处改动的"原 → 新"。数量、配色配码按 SKU（加新 SKU 时 orderItemId 是 null）；
 * 单价、资料包版本按款；交期是单头的要求交期。草稿保存整张覆盖
 */
@Data
@TableName("ord_change_item")
public class OrderChangeItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long changeId;

    private Integer seq;

    private ChangeType changeType;

    private Long orderItemId;

    private Long styleId;

    private Long styleSkuId;

    private Integer fromQty;
    private Integer toQty;

    private LocalDate fromDate;
    private LocalDate toDate;

    /** 这个款原来的单价；按尺码不一样、没填时是 null */
    private BigDecimal fromPrice;
    /** 新单价；新加的 SKU 也记它用的单价 */
    private BigDecimal toPrice;

    private Long fromTechPackId;
    private Long toTechPackId;
}
