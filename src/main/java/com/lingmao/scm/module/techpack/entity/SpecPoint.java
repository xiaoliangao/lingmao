package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 尺寸指示的一个部位（tp_spec_point，数据库设计 §9.8）；各尺码的尺寸在 SpecValue */
@Data
@TableName("tp_spec_point")
public class SpecPoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long techPackId;

    private Integer seq;

    /** 部位：存文字，手填的也能存 */
    private String point;

    /** 测量方法 */
    private String method;

    /** 公差 ±cm */
    private BigDecimal toleranceCm;

    /** 档差 cm */
    private BigDecimal gradeCm;

    private String remark;
}
