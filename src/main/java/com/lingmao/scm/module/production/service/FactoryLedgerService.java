package com.lingmao.scm.module.production.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.production.dto.LedgerMaterialQuery;
import com.lingmao.scm.module.production.dto.LedgerMaterialRowVO;
import com.lingmao.scm.module.production.dto.LedgerOrderQuery;
import com.lingmao.scm.module.production.dto.LedgerOrderRowVO;

import java.util.Map;

/** 工厂料账（RFC 015 §4.2）：按生产单、按工厂物料两个视图 */
public interface FactoryLedgerService {

    PageResult<LedgerOrderRowVO> orders(LedgerOrderQuery query);

    /** 每种料账状态各几张：IN_PROGRESS / TO_SETTLE / SETTLING / SETTLED → 张数 */
    Map<String, Long> counts(LedgerOrderQuery query);

    PageResult<LedgerMaterialRowVO> materials(LedgerMaterialQuery query);

    byte[] exportMaterials(LedgerMaterialQuery query);

    /** 待核销的生产单几张（首页"其他提醒"的 CONSUME_PENDING） */
    long toSettleCount();
}
