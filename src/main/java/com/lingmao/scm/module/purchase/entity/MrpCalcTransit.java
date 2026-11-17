package com.lingmao.scm.module.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 本次使用在途（数据库设计 §11.4）：算料行用了哪条采购行的在途、用多少（库存单位），eta 是算料时那行的预计到货 */
@Data
@TableName("mrp_calc_transit")
public class MrpCalcTransit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long calcItemId;

    private Long purchaseItemId;

    private BigDecimal qty;

    private LocalDate eta;
}
