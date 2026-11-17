package com.lingmao.scm.module.techpack.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/** 尺寸指示的一行（前端 SpecPoint） */
@Data
public class SpecPointVO {
    private Long id;
    private Integer seq;
    private String point;
    private String method;
    private BigDecimal toleranceCm;
    private BigDecimal gradeCm;
    private String remark;
    /** 尺码 → 尺寸 cm；没填的格子没有这个键 */
    private Map<String, BigDecimal> values;
}
