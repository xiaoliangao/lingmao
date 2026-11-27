package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.doc.DocStatus;

/**
 * 一张生产单的料账状态（RFC 015 §2）：不存，查询时按生产单状态和耗料核销单算（前端 LEDGER_STATUS）
 */
public enum LedgerStatus {
    /** 在制：已下单、生产中 */
    IN_PROGRESS,
    /** 待核销：已完成、已结案，还没有耗料核销单 */
    TO_SETTLE,
    /** 核销中：耗料核销单是草稿 */
    SETTLING,
    /** 已核销：耗料核销单已审核 */
    SETTLED;

    /** settleDocStatus 是没作废的耗料核销单的状态（DRAFT / APPROVED），没有是 null；不进料账的状态返回 null */
    public static LedgerStatus of(DocStatus prdStatus, String settleDocStatus) {
        if (prdStatus == DocStatus.APPROVED || prdStatus == DocStatus.RUNNING) {
            return IN_PROGRESS;
        }
        if (prdStatus != DocStatus.DONE && prdStatus != DocStatus.CLOSED) {
            return null;
        }
        if ("APPROVED".equals(settleDocStatus)) {
            return SETTLED;
        }
        return settleDocStatus == null ? TO_SETTLE : SETTLING;
    }
}
