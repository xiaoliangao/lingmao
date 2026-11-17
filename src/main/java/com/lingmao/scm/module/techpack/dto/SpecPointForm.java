package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/** 保存时一行尺寸指示；顺序 = 数组顺序 */
@Data
public class SpecPointForm {
    /** 部位，必填（Service 里按行号报） */
    @Size(max = 64, message = "部位最长 64 个字")
    private String point;
    @Size(max = 500, message = "测量方法最长 500 个字")
    private String method;
    private BigDecimal toleranceCm;
    private BigDecimal gradeCm;
    @Size(max = 255, message = "尺寸指示的备注最长 255 个字")
    private String remark;
    /** 尺码 → 尺寸 cm；空的格子不传或传 null */
    private Map<String, BigDecimal> values;
}
