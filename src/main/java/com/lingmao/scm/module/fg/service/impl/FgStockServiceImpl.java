package com.lingmao.scm.module.fg.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.SeekPage;
import com.lingmao.scm.common.page.BusinessDateRange;
import com.lingmao.scm.common.excel.BoundedExportRows;
import com.lingmao.scm.common.scope.ExportScope;
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
 * 库存按主体严格分账；接口权限独立于主体上下文，历史无库位输入使用 locationId=0。
 */
@Service
@RequiredArgsConstructor
public class FgStockServiceImpl implements FgStockService {

    /** 同步下载最多 1 万行；页面日志使用 seek，不限制只能访问前 1 万行。 */
    static final int LOG_LIMIT = 10_000;

    private final FgStockMapper stockMapper;
    private final FgStockLogMapper logMapper;
    private final FgStyles styles;
    private final FgExcel excel;
    private final FgStockLogCursor logCursor;
    private final UserService userService;

    @Override
    public PageResult<FgStockRowVO> page(FgStockQuery query) {
        IPage<FgStockRowVO> page = stockMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()), FgScope.company(query.getCompanyId()));
        fillSkus(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public PageResult<FgStockByStyleRowVO> byStyle(FgStockQuery query) {
        requireStyleFilters(query);
        IPage<Long> page = stockMapper.selectStylePage(new Page<>(query.getPage(), query.getSize()), query,
                Keywords.codes(query.getKeyword()), FgScope.company(query.getCompanyId()));
        return new PageResult<>(byStyle(page.getRecords(), query.getWarehouseId(), query.getCompanyId()), page.getTotal());
    }

    /** 每款一行：矩阵要的颜色、尺码、SKU，加上每个 SKU 的库存（几个仓合在一起，或者只看一个仓） */
    private List<FgStockByStyleRowVO> byStyle(List<Long> styleIds, Long warehouseId, long companyId) {
        if (styleIds.isEmpty()) {
            return List.of();
        }
        Map<Long, StyleOrderInfo> infos = styles.byId(styleIds);
        List<Long> skuIds = infos.values().stream().flatMap(s -> s.skus().stream()).map(StyleOrderInfo.Sku::id).distinct().toList();
        Map<Long, Long> qtys = stockMapper.sumBySkus(skuIds, warehouseId, companyId).stream()
                .collect(Collectors.toMap(FgStockMapper.SkuQty::styleSkuId, FgStockMapper.SkuQty::qty));
        return styleIds.stream().map(infos::get).filter(Objects::nonNull).map(s -> {
            FgStyleOptionVO o = FgStyleOptionVO.of(s);
            Map<String, Long> q = new LinkedHashMap<>();
            long total = 0;
            for (StyleOrderInfo.Sku k : s.skus()) {
                Long n = qtys.get(k.id());
                if (n != null && n != 0) {
                    q.put(String.valueOf(k.id()), n);
                    total += n;
                }
            }
            return new FgStockByStyleRowVO(companyId, o.styleId(), o.code(), o.name(), o.image(), o.sizes(), o.colors(), o.skus(), total, q);
        }).toList();
    }

    @Override
    public List<FgStockQtyVO> qty(long companyId, long warehouseId, List<Long> skuIds) {
        FgScope.company(companyId);
        if (warehouseId <= 0) throw new BizException(400, "请选仓库");
        List<Long> ids = FgScope.ids(skuIds, 100, "SKU");
        Map<Long, Integer> found = stockMapper.selectBySkus(companyId, warehouseId, ids).stream()
                .collect(Collectors.toMap(FgStock::getStyleSkuId, FgStock::getQty));
        return ids.stream().map(id -> new FgStockQtyVO(companyId, warehouseId, id, found.getOrDefault(id, 0))).toList();
    }

    @Override
    public SeekPage<FgStockLogRowVO> logs(FgStockLogQuery query) {
        long companyId = FgScope.company(query.getCompanyId());
        if (query.getSize() < 1 || query.getSize() > 100 || query.getPage() != 1)
            throw new BizException(400, "成品流水使用游标翻页，每页 1 至 100 条");
        BusinessDateRange.validate(query.getBizDateFrom(), query.getBizDateTo());
        var after = logCursor.decode(query);
        var fetched = logMapper.selectSeek(query, Keywords.codes(query.getKeyword()), companyId,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), (int) query.getSize() + 1);
        boolean more = fetched.size() > query.getSize();
        List<FgStockLogRowVO> rows = more ? fetched.subList(0, (int) query.getSize()) : fetched;
        fillLogs(rows);
        String next = more ? logCursor.encode(query, new FgStockLogCursor.Position(rows.getLast().getCreateTime(), rows.getLast().getId())) : null;
        return new SeekPage<>(rows, next, more);
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
                Keywords.codes(query.getKeyword()), FgScope.company(query.getCompanyId())).getRecords();
        BoundedExportRows.requireWithinLimit(rows.size(), Excel.MAX_EXPORT_ROWS, "成品库存");
        ExportScope.check(query.getIds(), rows, FgStockRowVO::getId, FgStockRowVO::getCompanyId);
        fillSkus(rows);
        return excel.exportStock(rows);
    }

    @Override
    public byte[] exportByStyle(FgStockQuery query) {
        requireStyleFilters(query);
        List<Long> ids = stockMapper.selectStylePage(new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false), query,
                Keywords.codes(query.getKeyword()), FgScope.company(query.getCompanyId())).getRecords();
        BoundedExportRows.requireWithinLimit(ids.size(), Excel.MAX_EXPORT_ROWS, "成品库存款式");
        return excel.exportByStyle(byStyle(ids, query.getWarehouseId(), query.getCompanyId()));
    }

    /** 明细库存 ID 只用于明细导出，按款查询不能接受后静默忽略。 */
    private static void requireStyleFilters(FgStockQuery query) {
        if (query.getIds() != null && !query.getIds().isEmpty())
            throw new BizException(400, "按款库存不支持明细编号筛选，请使用款式、仓库和主体筛选");
    }

    @Override
    public byte[] exportLogs(FgStockLogQuery query) {
        BusinessDateRange.validate(query.getBizDateFrom(), query.getBizDateTo());
        if (query.getCursor() != null && !query.getCursor().isBlank()) throw new BizException(400, "导出请使用筛选范围，不传翻页游标");
        List<FgStockLogRowVO> rows = logMapper.selectRowPage(new Page<>(1, LOG_LIMIT + 1, false), query,
                Keywords.codes(query.getKeyword()), FgScope.company(query.getCompanyId())).getRecords();
        if (rows.size() > LOG_LIMIT) {
            throw new BizException("库存日志超过 1 万行，请缩小日期范围再导出");
        }
        fillLogs(rows);
        return excel.exportLogs(rows);
    }
}
