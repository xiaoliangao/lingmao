package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 核价的"其他费用"一项（tp_cost_item，数据库设计 §9.7）：印绣花外加工、运费、包装这类按件摊的钱 */
@Data
@TableName("tp_cost_item")
public class CostItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long techPackId;

    private Integer seq;

    private String name;

    /** 元 / 件 */
    private BigDecimal amount;

    private String remark;
}
