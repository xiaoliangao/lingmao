package com.lingmao.scm.module.techpack.dto;

import java.math.BigDecimal;

/** 核价四块和合计（元 / 件，两位小数，前端 CostSummary） */
public record CostSummary(BigDecimal fabric, BigDecimal trim, BigDecimal labor, BigDecimal other, BigDecimal total) {
}
