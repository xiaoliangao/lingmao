package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 分码用量（tp_bom_item_size，数据库设计 §9.4）：用量方式是分码的行，每个尺码一个用量 */
@Data
@TableName("tp_bom_item_size")
public class BomItemSize {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bomItemId;

    private String sizeCode;

    /** 单件用量，库存单位 */
    private BigDecimal usageQty;
}
