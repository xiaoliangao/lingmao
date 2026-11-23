package com.lingmao.scm.module.fg.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.SeekPage;
import com.lingmao.scm.module.fg.dto.FgStockByStyleRowVO;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.dto.FgStockQtyVO;
import com.lingmao.scm.module.fg.dto.FgStockQuery;
import com.lingmao.scm.module.fg.dto.FgStockRowVO;

import java.util.List;

/** 成品库存、库存日志（docs/api/07 §3.6）：只读，库存只能由单据改 */
public interface FgStockService {

    PageResult<FgStockRowVO> page(FgStockQuery query);

    PageResult<FgStockByStyleRowVO> byStyle(FgStockQuery query);

    /** 一个仓几个 SKU 的现库存，没有库存行的返回 0 */
    List<FgStockQtyVO> qty(long companyId, long warehouseId, List<Long> skuIds);

    SeekPage<FgStockLogRowVO> logs(FgStockLogQuery query);

    byte[] export(FgStockQuery query);

    byte[] exportByStyle(FgStockQuery query);

    byte[] exportLogs(FgStockLogQuery query);
}
