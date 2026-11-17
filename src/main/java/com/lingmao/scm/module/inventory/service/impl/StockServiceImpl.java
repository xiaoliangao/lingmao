package com.lingmao.scm.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockQtyVO;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.inventory.service.StockService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 库存、库存日志的查询（docs/api/04 §3.6）。库存不是单据，不分数据范围 */
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final StockExcel excel;
    private final MaterialService materialService;
    private final UserService userService;

    @Override
    public PageResult<StockRowVO> page(StockQuery query) {
        IPage<StockRowVO> page = stockMapper.selectRowPage(query.toPage(), query, InvTexts.codesIn(query.getKeyword()));
        fillBriefs(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public PageResult<StockSummaryRowVO> summary(StockQuery query) {
        IPage<StockSummaryRowVO> page = stockMapper.selectSummaryPage(query.toPage(), query, InvTexts.codesIn(query.getKeyword()));
        fillBriefs(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public List<StockQtyVO> qty(long warehouseId, List<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, BigDecimal> stock = ids.isEmpty() ? Map.of()
                : stockMapper.selectBySkus(warehouseId, ids).stream().collect(Collectors.toMap(Stock::getMaterialSkuId, Stock::getQty));
        return ids.stream().map(k -> new StockQtyVO(k, stock.getOrDefault(k, BigDecimal.ZERO))).toList();
    }

    @Override
    public PageResult<StockLogRowVO> logs(StockLogQuery query) {
        IPage<StockLogRowVO> page = stockLogMapper.selectRowPage(query.toPage(), query);
        fillLogs(page.getRecords());
        return PageResult.of(page);
    }

    private void fillLogs(List<StockLogRowVO> rows) {
        fillBriefs(rows);
        Map<Long, String> names = userService.nicknames(rows.stream().map(StockLogRowVO::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        rows.forEach(r -> r.setOperatorName(r.getCreateBy() == null ? null : names.get(r.getCreateBy())));
    }

    /** 行上的物料名称、颜色、图（查询时只取了 materialSkuId） */
    private <T extends MaterialSkuBrief> void fillBriefs(List<T> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(rows.stream().map(MaterialSkuBrief::getMaterialSkuId).toList());
        rows.forEach(r -> r.fillFrom(briefs.get(r.getMaterialSkuId())));
    }

    private static <T> Page<T> all() {
        return new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false);
    }

    @Override
    public byte[] export(StockQuery query) {
        List<StockRowVO> rows = stockMapper.selectRowPage(all(), query, InvTexts.codesIn(query.getKeyword())).getRecords();
        fillBriefs(rows);
        return excel.stocks(rows);
    }

    @Override
    public byte[] exportSummary(StockQuery query) {
        List<StockSummaryRowVO> rows = stockMapper.selectSummaryPage(all(), query, InvTexts.codesIn(query.getKeyword())).getRecords();
        fillBriefs(rows);
        return excel.summary(rows);
    }

    @Override
    public byte[] exportLogs(StockLogQuery query) {
        List<StockLogRowVO> rows = stockLogMapper.selectRowPage(all(), query).getRecords();
        fillLogs(rows);
        return excel.logs(rows);
    }
}
