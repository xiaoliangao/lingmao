package com.lingmao.scm.module.settle.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;

/** 扣款登记（RFC 019 §5.2，docs/api/09 §3） */
public interface DeductionService {

    PageResult<DeductionRowVO> page(DeductionQuery query);

    /** 登记，返回 id */
    Long create(DeductionReq req);

    /** 改：进了没作废的对账单的不能改（1000）；返回新的 version */
    int update(long id, DeductionReq req);

    /** 删：进了没作废的对账单的不能删（1000） */
    void delete(long id);
}
