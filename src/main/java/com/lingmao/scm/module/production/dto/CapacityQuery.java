package com.lingmao.scm.module.production.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 工厂产能列表（GET /api/factory-capacities） */
@Data
public class CapacityQuery {
    /** 看哪个月，yyyy-MM；不填是这个月。列出这个月用的产能（这个月登记的，或沿用前面最近一次登记的） */
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "月份写成 2026-12 这样")
    private String month;
    /** 加工厂 */
    private Long factoryId;
    /** 查加工厂的简称、名称、编码和品类名 */
    private String keyword;
}
