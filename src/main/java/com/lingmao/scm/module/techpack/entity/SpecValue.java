package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 尺寸指示一格：部位 × 尺码的尺寸（tp_spec_value）；没填的格子不存行 */
@Data
@TableName("tp_spec_value")
public class SpecValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long specPointId;

    private String sizeCode;

    private BigDecimal valueCm;
}
