package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 问一行 BOM 的默认值（前端 BomDefaultReq） */
@Data
public class BomDefaultReq {
    @NotNull(message = "要选物料")
    private Long materialId;
    /** 页面上选了的供应商；null = 用物料的默认供应商 */
    private Long supplierId;
    /** 这一行配到的物料 SKU（去重）；还没配是空 */
    private List<Long> skuIds;
}
