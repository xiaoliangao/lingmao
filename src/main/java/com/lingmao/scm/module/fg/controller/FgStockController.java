package com.lingmao.scm.module.fg.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.fg.dto.FgStockByStyleRowVO;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.dto.FgStockQtyVO;
import com.lingmao.scm.module.fg.dto.FgStockQuery;
import com.lingmao.scm.module.fg.dto.FgStockRowVO;
import com.lingmao.scm.module.fg.service.FgStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 成品库存、库存日志（docs/api/07 §3.6）：只读 */
@Tag(name = "成品库存")
@RestController
@RequiredArgsConstructor
public class FgStockController {

    private final FgStockService fgStockService;
    private final Clock clock;

    @Operation(summary = "库存明细：一个仓一个 SKU 一行")
    @GetMapping("/api/fg-stocks")
    @PreAuthorize("hasAuthority('fg:stock:list')")
    public R<PageResult<FgStockRowVO>> page(@Valid FgStockQuery query) {
        return R.ok(fgStockService.page(query));
    }

    @Operation(summary = "按款：一款一行，带颜色 × 尺码矩阵")
    @GetMapping("/api/fg-stocks/by-style")
    @PreAuthorize("hasAuthority('fg:stock:list')")
    public R<PageResult<FgStockByStyleRowVO>> byStyle(@Valid FgStockQuery query) {
        return R.ok(fgStockService.byStyle(query));
    }

    @Operation(summary = "一个仓几个 SKU 的现库存（填出库单时看），没有库存记录的是 0")
    @GetMapping("/api/fg-stocks/qty")
    @PreAuthorize("hasAnyAuthority('fg:stock:list', 'fg:doc:add')")
    public R<List<FgStockQtyVO>> qty(@RequestParam("warehouseId") long warehouseId, @RequestParam("skuIds") List<Long> skuIds) {
        return R.ok(fgStockService.qty(warehouseId, skuIds));
    }

    @Operation(summary = "导出库存明细")
    @GetMapping("/api/fg-stocks/export")
    @PreAuthorize("hasAuthority('fg:stock:export')")
    public ResponseEntity<byte[]> export(@Valid FgStockQuery query) {
        return Excel.download(Excel.exportFileName("成品库存", LocalDate.now(clock)), fgStockService.export(query));
    }

    @Operation(summary = "导出按款（尺码横排）")
    @GetMapping("/api/fg-stocks/by-style/export")
    @PreAuthorize("hasAuthority('fg:stock:export')")
    public ResponseEntity<byte[]> exportByStyle(@Valid FgStockQuery query) {
        return Excel.download(Excel.exportFileName("成品库存按款", LocalDate.now(clock)), fgStockService.exportByStyle(query));
    }

    @Operation(summary = "库存日志，新的在前（只能翻到前 1 万行）")
    @GetMapping("/api/fg-stock-logs")
    @PreAuthorize("hasAuthority('fg:stock:list')")
    public R<PageResult<FgStockLogRowVO>> logs(@Valid FgStockLogQuery query) {
        return R.ok(fgStockService.logs(query));
    }

    @Operation(summary = "导出库存日志（最多 1 万行）")
    @GetMapping("/api/fg-stock-logs/export")
    @PreAuthorize("hasAuthority('fg:stock:export')")
    public ResponseEntity<byte[]> exportLogs(@Valid FgStockLogQuery query) {
        return Excel.download(Excel.exportFileName("成品库存日志", LocalDate.now(clock)), fgStockService.exportLogs(query));
    }
}
