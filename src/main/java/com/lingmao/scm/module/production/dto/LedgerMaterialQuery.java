package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工厂料账 · 按工厂物料（GET /api/factory-ledger/materials） */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerMaterialQuery extends PageQuery {
    /** 加工厂（供应商 id） */
    private Long factoryId;
    /** 物料 SKU 码、物料编码、物料名称 */
    private String keyword;
}
