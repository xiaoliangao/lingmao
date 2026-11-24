package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.SeekPage;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockQtyVO;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;

import java.util.List;

/** 库存、库存日志（docs/api/04 §3.6）：只读。改库存只有过账一条路（StockLedger） */
public interface StockService {

    PageResult<StockRowVO> page(StockQuery query);

    PageResult<StockSummaryRowVO> summary(StockQuery query);

    /** 一个仓几个 SKU 的现库存，按请求的顺序，没有库存记录的是 0 */
    List<StockQtyVO> qty(long warehouseId, List<Long> skuIds);

    PageResult<StockLogRowVO> logs(StockLogQuery query);

    SeekPage<StockLogRowVO> seekLogs(StockLogQuery query);

    com.lingmao.scm.common.excel.ExcelExport exportPlan(StockQuery query);

    com.lingmao.scm.common.excel.ExcelExport exportSummaryPlan(StockQuery query);

    byte[] export(StockQuery query);

    byte[] exportSummary(StockQuery query);

    byte[] exportLogs(StockLogQuery query);
}
