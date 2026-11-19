package com.lingmao.scm.module.fg.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.fg.dto.FgSkuFields;
import com.lingmao.scm.module.fg.dto.FgStockByStyleRowVO;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.dto.FgStockQtyVO;
import com.lingmao.scm.module.fg.dto.FgStockQuery;
import com.lingmao.scm.module.fg.dto.FgStockRowVO;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import com.lingmao.scm.module.fg.entity.FgStock;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.fg.service.FgStockService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 成品库存、库存日志（docs/api/07 §3.6）。只读：库存只由成品单据审核、反审核改（FgLedger）。
 * 成品库存不分主体、不分品类线（RFC 008 §3.5：一个仓里的货谁都能发，看库存的人都看得到）
 */
@Service
@RequiredArgsConstructor
public class FgStockServiceImpl implements FgStockService {

    /** 日志只翻、只导前 1 万行（docs/api/07 §3.6，和物料的一样） */
    static final int LOG_LIMIT = 10_000;

    private final FgStockMapper stockMapper;
    private final FgStockLogMapper logMapper;
    private final FgStyles styles;
    private final FgExcel excel;
    private final UserService userService;

    @Override
    public PageResult<FgStockRowVO> page(FgStockQuery query) {
        IPage<FgStockRowVO> page = stockMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
        fillSkus(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public PageResult<FgStockByStyleRowVO> byStyle(FgStockQuery query) {
        IPage<Long> page = stockMapper.selectStylePage(new Page<>(query.getPage(), query.getSize()), query,
                Keywords.codes(query.getKeyword()));
        return new PageResult<>(byStyle(page.getRecords(), query.getWarehouseId()), page.getTotal());
    }

    /** 每款一行：矩阵要的颜色、尺码、SKU，加上每个 SKU 的库存（几个仓合在一起，或者只看一个仓） */
    private List<FgStockByStyleRowVO> byStyle(List<Long> styleIds, Long warehouseId) {
        if (styleIds.isEmpty()) {
            return List.of();
        }
        Map<Long, StyleOrderInfo> infos = styles.byId(styleIds);
        Map<Long, Integer> qtys = stockMapper.sumByStyles(styleIds, warehouseId).stream()
                .collect(Collectors.toMap(FgStockMapper.SkuQty::styleSkuId, FgStockMapper.SkuQty::qty));
        return styleIds.stream().map(infos::get).filter(Objects::nonNull).map(s -> {
            FgStyleOptionVO o = FgStyleOptionVO.of(s);
            Map<String, Integer> q = new LinkedHashMap<>();
            int total = 0;
            for (StyleOrderInfo.Sku k : s.skus()) {
                Integer n = qtys.get(k.id());
                if (n != null && n != 0) {
                    q.put(String.valueOf(k.id()), n);
                    total += n;
                }
            }
            return new FgStockByStyleRowVO(o.styleId(), o.code(), o.name(), o.image(), o.sizes(), o.colors(), o.skus(), total, q);
        }).toList();
    }

    @Override
    public List<FgStockQtyVO> qty(long warehouseId, List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        if (skuIds.size() > 1000) {
            throw new BizException(400, "一次最多查 1000 个 SKU");
        }
        Map<Long, Integer> found = stockMapper.selectBySkus(warehouseId, skuIds).stream()
                .collect(Collectors.toMap(FgStock::getStyleSkuId, FgStock::getQty));
        return new LinkedHashSet<>(skuIds).stream().map(id -> new FgStockQtyVO(id, found.getOrDefault(id, 0))).toList();
    }

    @Override
    public PageResult<FgStockLogRowVO> logs(FgStockLogQuery query) {
        if ((long) (query.getPage() - 1) * query.getSize() >= LOG_LIMIT) {
            throw new BizException(400, "日志只能翻到前 1 万行，请缩小日期范围，或按款、SKU 筛");
        }
        IPage<FgStockLogRowVO> page = logMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
        fillLogs(page.getRecords());
        return PageResult.of(page);
    }

    private void fillLogs(List<FgStockLogRowVO> rows) {
        fillSkus(rows);
        Set<Long> userIds = rows.stream().map(FgStockLogRowVO::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userService.nicknames(userIds);
        rows.forEach(r -> r.setCreateByName(r.getCreateBy() == null ? null : names.get(r.getCreateBy())));
    }

    private void fillSkus(List<? extends FgSkuFields> rows) {
        Map<Long, StyleOrderInfo> bySku = styles.bySku(rows.stream().map(FgSkuFields::getStyleSkuId).toList());
        rows.forEach(r -> r.fillSku(r.getStyleSkuId(), bySku.get(r.getStyleSkuId())));
    }

    @Override
    public byte[] export(FgStockQuery query) {
        List<FgStockRowVO> rows = stockMapper.selectRowPage(new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false), query,
                Keywords.codes(query.getKeyword())).getRecords();
        fillSkus(rows);
        return excel.exportStock(rows);
    }

    @Override
    public byte[] exportByStyle(FgStockQuery query) {
        List<Long> ids = stockMapper.selectStylePage(new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false), query,
                Keywords.codes(query.getKeyword())).getRecords();
        return excel.exportByStyle(byStyle(ids, query.getWarehouseId()));
    }

    @Override
    public byte[] exportLogs(FgStockLogQuery query) {
        List<FgStockLogRowVO> rows = logMapper.selectRowPage(new Page<>(1, LOG_LIMIT + 1, false), query,
                Keywords.codes(query.getKeyword())).getRecords();
        if (rows.size() > LOG_LIMIT) {
            throw new BizException("库存日志超过 1 万行，请缩小日期范围再导出");
        }
        fillLogs(rows);
        return excel.exportLogs(rows);
    }
}
