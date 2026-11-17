package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.common.doc.DocStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 给生产用的订单（docs/api/02 §3.12、05 §3.9）：排单、生产单回写已排产和已回货、按生产进度改订单状态都先拿它
 *
 * @param commitDate 承诺交期（审核时填的），排单的默认交期按它倒推；没有用要求交期
 * @param items      全部行（变更单减成 0 的也在），按行号
 */
public record OrderForProduction(long id, String no, DocStatus status, long companyId, LocalDate commitDate, LocalDate requiredDate,
                                 long merchId, List<Line> items) {

    /** 一行：techPackId 是审核时锁定的生产 BOM */
    public record Line(long id, long styleId, long styleSkuId, int qty, int scheduledQty, int returnedQty, Long techPackId) {
    }
}
