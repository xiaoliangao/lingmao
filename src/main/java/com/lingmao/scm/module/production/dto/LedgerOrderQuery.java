package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工厂料账 · 按生产单（GET /api/factory-ledger/orders） */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerOrderQuery extends PageQuery {
    /** 生产单号、款号 */
    private String keyword;
    /** 料账状态：IN_PROGRESS 在制 / TO_SETTLE 待核销 / SETTLING 核销中 / SETTLED 已核销；不传是全部 */
    private LedgerStatus status;
    /** 加工厂（供应商 id） */
    private Long factoryId;
    /** 种类：OUTSOURCE 外发加工 / BUY 成品采购 */
    private String kind;
}
