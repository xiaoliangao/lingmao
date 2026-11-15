package com.lingmao.scm.module.basic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** 新建物料：物料自己的列 + 第一批 SKU（颜色 × 规格，规则同批量加 SKU） */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialCreateReq extends MaterialSaveReq {

    @Size(max = 200, message = "一次最多选 200 个颜色")
    private List<Long> colorIds = new ArrayList<>();

    @Valid
    @Size(max = 100, message = "一次最多填 100 个规格")
    private List<SkuSpecReq> specs = new ArrayList<>();
}
