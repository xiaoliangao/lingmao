package com.lingmao.scm.module.settle.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 扣款登记（RFC 019 §5.2，docs/api/09 §3） */
public interface DeductionService {

    PageResult<DeductionRowVO> page(DeductionQuery query);

    /** 登记，返回 id */
    Long create(DeductionReq req);

    /** 改：进了没作废的对账单的不能改（1000）；返回新的 version */
    int update(long id, DeductionReq req);

    /** 删：进了没作废的对账单的不能删（1000） */
    void delete(long id);

    /**
     * 别的单据（质检记录，RFC 019 §6.2）登记的扣款：主体、供应商、日期、金额、原因都由来源定。
     * 扣款登记页上不给改、删（update、delete 回 1000），只能由来源调下面三个方法
     *
     * @param sourceType 来源类型（QC_INSPECTION）
     * @param sourceId   来源 id
     * @param refNo      来源单号，显示在扣款登记和对账单上
     */
    record Sourced(String sourceType, long sourceId, long companyId, long partnerId, LocalDate bizDate,
                   BigDecimal amount, String reason, String refNo) {
    }

    /** 登记，返回 id。调用方自己查过权限、主体和日期 */
    long createSourced(Sourced d);

    /**
     * 改金额、日期、原因。进了没作废的对账单的：金额、日期变了报 1000；只有原因变了就不改（对账单上已经抄过去了）
     */
    void updateSourced(long id, Sourced d);

    /** 删：进了没作废的对账单的报 1000 */
    void deleteSourced(long id);
}
