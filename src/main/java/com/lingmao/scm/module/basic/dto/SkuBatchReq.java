package com.lingmao.scm.module.basic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量加 SKU（RFC 003 §7.2）：选的颜色 × 填的规格两两组合，已有的跳过。
 * 都不填就是一个不带颜色、规格的 SKU（码 = 物料编码）
 */
@Data
public class SkuBatchReq {

    @Size(max = 200, message = "一次最多选 200 个颜色")
    private List<Long> colorIds = new ArrayList<>();

    @Valid
    @Size(max = 100, message = "一次最多填 100 个规格")
    private List<SkuSpecReq> specs = new ArrayList<>();
}
